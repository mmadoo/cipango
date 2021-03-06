package org.cipango.console;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.Date;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

import javax.management.AttributeNotFoundException;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanException;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.ReflectionException;

import org.cipango.console.util.ObjectNameFactory;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.jrobin.core.RrdDb;
import org.jrobin.core.RrdDbPool;
import org.jrobin.core.RrdDef;
import org.jrobin.core.RrdDefTemplate;
import org.jrobin.core.RrdException;
import org.jrobin.core.Sample;
import org.jrobin.graph.RrdGraph;
import org.jrobin.graph.RrdGraphDef;
import org.jrobin.graph.RrdGraphDefTemplate;
import org.xml.sax.InputSource;

public class StatisticGraph
{
	private static final String RDD_TEMPLATE_FILE_NAME = "rddTemplate.xml";
	
	private static final ObjectName OPERATING_SYSTEM = ObjectNameFactory.create("java.lang:type=OperatingSystem");
	private static final ObjectName GARBAGE_COLLECTORS = ObjectNameFactory.create("java.lang:type=GarbageCollector,*");
	private static final ObjectName THREAD = ObjectNameFactory.create(ManagementFactory.THREAD_MXBEAN_NAME);
	
	
	public enum GraphType
	{
		SESSIONS("rddSessionsGraph.xml"),
		MEMORY("rddMemoryGraphTemplate.xml"),
		MESSAGES("rddMessagesGraphTemplate.xml"),
		CPU("rddCpuGraphTemplate.xml"),
		THREADS("rddThreadsGraph.xml");
		
		private RrdGraphDefTemplate _template;
		
		private GraphType(String resourceName)
		{
			try
			{
				InputStream templateGraph = getClass().getResourceAsStream(resourceName);
				_template = new RrdGraphDefTemplate(new InputSource(templateGraph));
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		}
		
		public RrdGraphDefTemplate getTemplate()
		{
			return _template;
		}
	}
		
	private long _refreshPeriod = -1; // To ensure that the stat will start if
										// needed at startup
	private long _timeoutPeriod = 60000; // Timeout before a retry is done after exception when getting connection
	private StatisticGraphTask _task;
	private TimerTask _startTask;
	private RrdDbPool _rrdPool;
	private String _rrdPath;
	private JmxConnection _connection;
	private String _dataFileName;
	private ObjectName _threadPool;

	private Timer _statTimer;

	private Logger _logger = Log.getLogger(StatisticGraph.class);

	private boolean _started = false;
	private boolean _cpuStatAvailable = false;
	private boolean _systemCpuStatAvailable = false;
	private ObjectName[] _contexts;
	private ObjectName[] _sessionManagers;

	public StatisticGraph(JmxConnection connection, Timer timer) throws AttributeNotFoundException,
			InstanceNotFoundException, MBeanException, ReflectionException, IOException, RrdException
	{
		_connection = connection;
		_rrdPool = RrdDbPool.getInstance();
		_statTimer = timer;
	}

	/**
	 * Sets the refresh period for statistics in milliseconds. If the period has changed, write
	 * statictics immediatly and reschedule the timer with <code>statRefreshPeriod</code>.
	 * 
	 * @param statRefreshPeriod The statistics refresh period in seconds or <code>-1</code> to
	 *            disabled refresh.
	 */
	public void setRefreshPeriod(long statRefreshPeriod)
	{
		if (_refreshPeriod != statRefreshPeriod)
		{
			_refreshPeriod = statRefreshPeriod;
			if (_task != null)
			{
				_task.run();
				_task.cancel();
			}

			_task = new StatisticGraphTask();

			if (_refreshPeriod != -1)
			{
				_statTimer.schedule(_task, _refreshPeriod);
			}
		}
	}
	
	public void reset()
	{
		try
		{
			RrdDb rrdDb = _rrdPool.requestRrdDb(_rrdPath);
			Sample sample = rrdDb.createSample();
			MBeanServerConnection mbsc = _connection.getMbsc();
			sample.setValue("sessions", getSessions(mbsc));
			MemoryUsage r = getMemory().getHeapMemoryUsage();
			sample.setValue("maxMemory", r.getMax());
			sample.setValue("totalMemory", r.getCommitted());
			sample.setValue("usedMemory", r.getUsed());
			// No values are set for incomingMessages, outgoingMessages to reset these counters.
			sample.update();
			_rrdPool.release(rrdDb);
		}
		catch (Exception e)
		{
			_logger.warn("Unable to reset statistics", e);
		}
	}

	protected long getSessions(MBeanServerConnection mbsc) throws AttributeNotFoundException, InstanceNotFoundException, MBeanException, ReflectionException, IOException
	{
		ObjectName[] contexts =  new SipManager(mbsc).getContexts();

		
		if (hasContextsChanged(contexts))
		{
			boolean valid = true;
			_logger.debug("SIP Contexts have changed");
			_sessionManagers = new ObjectName[contexts.length];

			for (int i = 0; i < contexts.length; i++)
			{
				if (contexts[i] != null)
				{
					ObjectName sessionHandler = (ObjectName) mbsc.getAttribute(contexts[i], "sessionHandler");
					if (sessionHandler != null)
						_sessionManagers[i] = (ObjectName) mbsc.getAttribute(sessionHandler, "sessionManager");
				}
				if (_sessionManagers[i] == null)
				{
					_logger.debug("Could not get session manager for context {}", contexts[i]);
					valid = false;
				}
			}
			if (valid)
				_contexts = contexts;
		}
		
		long sessions = 0;
		for (ObjectName sessionManager : _sessionManagers)
		{
			if (sessionManager != null)
				sessions += (Long) mbsc.getAttribute(sessionManager, "sessions");
		}
		return sessions;
	}
	
	private boolean hasContextsChanged(ObjectName[] contexts)
	{
		if (_contexts == contexts)
			return false;
		if (_contexts == null || _contexts.length != contexts.length)
			return true;
		
		for (int i = 0; i < contexts.length; i++)
			if (!_contexts[i].equals(contexts[i]))
				return true;
		return false;
	}
	
	/**
	 * Returns <code>true</code> if update has been successfully done.
	 * @return
	 */
	public boolean updateDb()
	{
		try
		{
			MBeanServerConnection mbsc;
			try
			{
				mbsc = getMbsc();
			}
			catch (Exception e) 
			{
				return false;
			}
			
			RrdDb rrdDb = _rrdPool.requestRrdDb(_rrdPath);
			Sample sample = rrdDb.createSample();
			sample.setValue("sessions", getSessions(mbsc));
			
			MemoryUsage r = getMemory().getHeapMemoryUsage();

			sample.setValue("maxMemory", r.getMax());
			sample.setValue("totalMemory", r.getCommitted());
			sample.setValue("usedMemory", r.getUsed());
			if (mbsc.isRegistered(SipManager.SERVER))
			{
				sample.setValue("incomingMessages",
						(Long) mbsc.getAttribute(SipManager.SERVER, "messagesReceived"));
				sample.setValue("outgoingMessages",
						(Long) mbsc.getAttribute(SipManager.SERVER, "messagesSent"));
			}
			
			int nbCpu = (Integer) mbsc.getAttribute(OPERATING_SYSTEM, "AvailableProcessors");
			if (_cpuStatAvailable)
			{
				long processCpuTime = (Long) mbsc.getAttribute(OPERATING_SYSTEM, "ProcessCpuTime");
				sample.setValue("cpu", processCpuTime / nbCpu);
				
				if (_systemCpuStatAvailable)
					sample.setValue("systemCpu", (Double) mbsc.getAttribute(OPERATING_SYSTEM, "SystemCpuLoad") * 100);
			}
			
			long timeInGc = 0;
			Set<ObjectName> garbageCollections = mbsc.queryNames(GARBAGE_COLLECTORS, null);
			for (ObjectName objectName : garbageCollections)
			{
				timeInGc += (Long) mbsc.getAttribute(objectName, "CollectionTime");
			}
			sample.setValue("timeInGc", timeInGc / nbCpu);
			
			sample.setValue("totalThreads", (Integer) mbsc.getAttribute(THREAD, "ThreadCount"));
			if (_threadPool == null)
				_threadPool = (ObjectName) getMbsc().getAttribute(SipManager.SERVER, "threadPool");
				
			long threadsInPool =  (Integer) mbsc.getAttribute(_threadPool, "threads");
			sample.setValue("activeThreadsInPool", threadsInPool - (Integer) mbsc.getAttribute(_threadPool, "idleThreads"));
			sample.setValue("threadsInPool",threadsInPool);
			
			sample.update();
			_rrdPool.release(rrdDb);
			return true;
		}
		catch (Exception e)
		{
			_logger.warn("Unable to set statistics", e);
			return false;
		}
	}

	public byte[] createGraphAsPng(Date start, Date end, RrdGraphDefTemplate graphTemplate)
	{
		try
		{
			RrdDb rrdDb = _rrdPool.requestRrdDb(_rrdPath);
			_rrdPool.release(rrdDb);
			graphTemplate.setVariable("start", start);
			graphTemplate.setVariable("end", end);
			graphTemplate.setVariable("rrd", _rrdPath);
			// create graph finally
			RrdGraphDef gDef = graphTemplate.getRrdGraphDef();
			RrdGraph graph = new RrdGraph(gDef);
			return graph.getRrdGraphInfo().getBytes();
		}
		catch (Exception e)
		{
			_logger.warn("Unable to create graph", e);
			return null;
		}
	}
	
	/**
	 * Create a graph of the last <code>time</code> seconds.
	 * 
	 * @param time
	 * @return The PNG image.
	 */
	public byte[] createGraphAsPng(long time, String type)
	{
		long start = System.currentTimeMillis() - time * 1000;
		long end = System.currentTimeMillis() - 2500; // Remove last 2,5 seconds due to bug with Jrobin LAST function
		return createGraphAsPng(new Date(start), new Date(end), GraphType.valueOf(type.toUpperCase()).getTemplate());
	}

	public void setDataFileName(String name)
	{
		_dataFileName = name;
	}

	public synchronized void start()
	{
		if (_started)
			return;
		try
		{
			_threadPool = (ObjectName) getMbsc().getAttribute(SipManager.SERVER, "threadPool");
			
			if (_dataFileName == null)
			{
				String name;
				if (_connection.isLocal())
					name = "statistics.rdd";
				else
					name = "statistics-" + _connection.getId().replace(":", "-") + ".rdd";
				_dataFileName = System.getProperty("jetty.home", ".") + "/logs/" + name;
			}

			File rrdFile = new File(_dataFileName);
			_rrdPath = rrdFile.getAbsolutePath();

			if (getMbsc().isRegistered(OPERATING_SYSTEM))
			{
				try
				{
					getMbsc().getAttribute(OPERATING_SYSTEM, "ProcessCpuTime");
					_cpuStatAvailable = true;
					
					getMbsc().getAttribute(OPERATING_SYSTEM, "SystemCpuLoad");
					_systemCpuStatAvailable = true;
				} 
				catch (Throwable e) 
				{
				}
			}
			
			
			if (!rrdFile.exists())
			{
				InputStream templateIs = getClass().getResourceAsStream(RDD_TEMPLATE_FILE_NAME);

				RrdDefTemplate defTemplate = new RrdDefTemplate(new InputSource(templateIs));

				defTemplate.setVariable("path", _rrdPath);
				defTemplate.setVariable("start", new Date(System.currentTimeMillis()));
				RrdDef rrdDef = defTemplate.getRrdDef();

				RrdDb rrdDb = _rrdPool.requestRrdDb(rrdDef);
				rrdDb.getRrdDef().getStep();
				_rrdPool.release(rrdDb);
			}
//			else
//				updateDb();

			RrdDb rrdDb = _rrdPool.requestRrdDb(_rrdPath);
			setRefreshPeriod(rrdDb.getRrdDef().getStep() * 1000);
			_rrdPool.release(rrdDb);
			_started = true;
		}
		catch (Exception e)
		{
			_started = false;
			_logger.warn("Unable to create RRD", e);

			_startTask = new StartTask();
			_statTimer.schedule(_startTask, _timeoutPeriod);
		}
	}

	public boolean isStarted()
	{
		return _started;
	}

	public synchronized void stop()
	{
		_started = false;
		if (_task != null)
			_task.cancel();
		if (_startTask != null)
			_startTask.cancel();
		_refreshPeriod = -1;	
	}
	
	public MBeanServerConnection getMbsc()
	{
		return _connection.getMbsc();
	}

	public MemoryMXBean getMemory() throws IOException
	{
		return ManagementFactory.newPlatformMXBeanProxy(getMbsc(), ManagementFactory.MEMORY_MXBEAN_NAME, MemoryMXBean.class);
	}

	class StatisticGraphTask extends TimerTask
	{

		public void run()
		{
			synchronized (StatisticGraph.this)
			{
				boolean success = updateDb();
				if (_started)
					_statTimer.schedule(new StatisticGraphTask(), success ? _refreshPeriod : _timeoutPeriod);
			}
		}

	}
	
	class StartTask extends TimerTask
	{
		public void run()
		{
			start();
		}

	}

}

// ========================================================================
// Copyright 2006-2013 NEXCOM Systems
// ------------------------------------------------------------------------
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at 
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// ========================================================================
package org.cipango.tests.integration;

import static org.junit.Assert.*;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.annotation.SipListener;
import javax.servlet.sip.annotation.SipServlet;

import org.cipango.tests.AbstractServlet;

@SuppressWarnings("serial")
@SipServlet(name="org.cipango.tests.integration.ServletContextListenerTest", loadOnStartup=1)
@SipListener
public class ServletContextListenerServlet extends AbstractServlet implements ServletContextListener
{
	private int _init = 0;
	private String _error;
	private String _webContextListener;
	
	public void contextInitialized(ServletContextEvent sce)
	{
		_init++;
	}

	public void contextDestroyed(ServletContextEvent sce)
	{
		_init--;
	}
	
	@Override
	public void init()
	{
		if (_init != 1)
			_error = "Context initialized not called before servlet init";
		_webContextListener = (String) getServletContext().getAttribute(WebContextListener.class.getName());
	}
	
	public void testInit(SipServletRequest request) throws Exception
	{
		assertEquals(1, _init);
		assertNull(_error, _error);
		request.createResponse(SipServletResponse.SC_OK).send();
	}
	
	public void testInitFromWeb(SipServletRequest request) throws Exception
	{
		String s = (String) getServletContext().getAttribute(WebContextListener.class.getName());
		assertNotNull("Servlet context listener declared in web.xml has not been called", s);
		assertNotNull("Servlet context listener declared in web.xml has been called after sip servlet init", _webContextListener);
		if (!"".equals(s))
			fail(s);
		request.createResponse(SipServletResponse.SC_OK).send();
	}

}

// ========================================================================
// Copyright 2008-2009 NEXCOM Systems
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

package org.cipango.server.util;

import javax.servlet.sip.URI;

public class ReadOnlyURI extends URIProxy
{
    static final long serialVersionUID = 1584249591435836163L;
    
	public ReadOnlyURI(URI uri)
	{
		super(uri);
	}
	
	@Override
	public void removeParameter(String arg0) 
	{
		throw new IllegalStateException("Read-only");
	}

	@Override
	public void setParameter(String arg0, String arg1) 
	{
		throw new IllegalStateException("Read-only");
	}
}

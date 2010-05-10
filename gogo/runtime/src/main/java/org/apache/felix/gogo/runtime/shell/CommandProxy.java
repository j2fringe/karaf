/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.felix.gogo.runtime.shell;

import java.util.List;

import org.osgi.service.command.Function;
import org.osgi.service.command.CommandSession;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.BundleContext;

public class CommandProxy extends Reflective implements Function
{
    private BundleContext context;
    private ServiceReference reference;
    private String function;
    private Object target;

    public CommandProxy(BundleContext context, ServiceReference reference, String function)
    {
        this.context = context;
        this.reference = reference;
        this.function = function;
    }
    
    public CommandProxy(Object target, String function)
    {
        this.function = function;
        this.target = target;
    }

    public Object execute(CommandSession session, List<Object> arguments)
        throws Exception
    {
        Object tgt = (context != null ? context.getService(reference) : target);
        
        try
        {
            if (tgt instanceof Function)
            {
                return ((Function) tgt).execute(session, arguments);
            }
            else
            {
                return method(session, tgt, function, arguments);
            }
        }
        finally
        {
            if (context != null)
            {
                context.ungetService(reference);
            }
        }
    }
}
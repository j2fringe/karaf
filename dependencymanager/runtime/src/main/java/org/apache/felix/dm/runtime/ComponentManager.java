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
package org.apache.felix.dm.runtime;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.List;

import org.apache.felix.dm.DependencyManager;
import org.apache.felix.dm.dependencies.BundleDependency;
import org.apache.felix.dm.dependencies.ConfigurationDependency;
import org.apache.felix.dm.dependencies.Dependency;
import org.apache.felix.dm.dependencies.ResourceDependency;
import org.apache.felix.dm.dependencies.ServiceDependency;
import org.apache.felix.dm.dependencies.TemporalServiceDependency;
import org.apache.felix.dm.service.Service;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.SynchronousBundleListener;
import org.osgi.service.log.LogService;

/**
 * This class parses service descriptors generated by the annotation bnd processor.
 * The descriptors are located under OSGI-INF/dependencymanager directory. Such files are actually 
 * referenced by a specific "DependendencyManager-Component" manifest header.
 */
public class ComponentManager implements SynchronousBundleListener
{
    private HashMap<Bundle, List<Service>> m_services = new HashMap<Bundle, List<Service>>();
    private LogService m_logService; // Injected
    private BundleContext m_bctx; // Injected

    /**
     * Starts our Service (at this point, we have been injected with our bundle context, as well
     * as with our log service. We'll listen to bundle start/stop events (we implement the 
     * SynchronousBundleListener interface).
     */
    protected void start()
    {
        for (Bundle b : m_bctx.getBundles())
        {
            if (b.getState() == Bundle.ACTIVE)
            {
                bundleChanged(new BundleEvent(Bundle.ACTIVE, b));
            }
        }
        m_bctx.addBundleListener(this);
    }

    /**
     * Stops our service. We'll stop all activated DependencyManager services.
     */
    protected void stop()
    {
        for (List<Service> services : m_services.values())
        {
            for (Service service : services)
            {
                service.stop();
            }
        }
        m_services.clear();
    }

    /**
     * Handle a bundle event, and eventually parse started bundles.
     */
    public void bundleChanged(BundleEvent event)
    {
        Bundle b = event.getBundle();
        if (b.getState() == Bundle.ACTIVE)
        {
            bundleStarted(b);
        }
        else if (b.getState() == Bundle.STOPPING)
        {
            bundleStopped(b);
        }
    }

    /**
     * Checks if a started bundle have some DependencyManager descriptors 
     * referenced in the "DependencyManager-Component" OSGi header.
     * @param b the started bundle.
     */
    void bundleStarted(Bundle b)
    {
        String descriptorPaths = (String) b.getHeaders().get("DependencyManager-Component");
        if (descriptorPaths == null)
        {
            return;
        }

        for (String descriptorPath : descriptorPaths.split(","))
        {
            URL descriptorURL = b.getEntry(descriptorPath);
            if (descriptorURL == null)
            {
                m_logService.log(LogService.LOG_ERROR,
                    "DependencyManager component descriptor not found: " + descriptorPath);
                continue;
            }
            loadDescriptor(b, descriptorURL);
        }
    }

    /**
     * Load a DependencyManager component descriptor from a given bundle.
     * @param b
     * @param descriptorURL
     */
    private void loadDescriptor(Bundle b, URL descriptorURL)
    {
        m_logService.log(LogService.LOG_DEBUG, "Parsing descriptor " + descriptorURL
            + " from bundle " + b.getSymbolicName());

        BufferedReader in = null;
        try
        {
            DescriptorParser parser = new DescriptorParser(m_logService);
            in = new BufferedReader(new InputStreamReader(descriptorURL.openStream()));
            DependencyManager dm = new DependencyManager(b.getBundleContext());
            Service service = null;
            String line;

            while ((line = in.readLine()) != null)
            {
                switch (parser.parse(line))
                {
                    case Service:
                        service = createService(b, dm, parser);
                        break;

                    case AspectService:
                        service = createAspectService(b, dm, parser);
                        break;
                        
                    case AdapterService:
                        service = createAdapterService(b, dm, parser);
                        break;
                        
                    case BundleAdapterService:
                        service = createBundleAdapterService(b, dm, parser);
                        break;

                    case ResourceAdapterService:
                        service = createResourceAdapterService(b, dm, parser);
                        break;
                        
                    case FactoryConfigurationAdapterService:
                        service = createFactoryConfigurationAdapterService(b, dm, parser);
                        break;

                    case ServiceDependency:
                        checkServiceParsed(service);
                        service.add(createServiceDependency(b, dm, parser, false));
                        break;

                    case TemporalServiceDependency:
                        checkServiceParsed(service);
                        service.add(createServiceDependency(b, dm, parser, true));
                        break;

                    case ConfigurationDependency:
                        checkServiceParsed(service);
                        service.add(createConfigurationDependency(b, dm, parser));
                        break;
                        
                    case BundleDependency:
                        checkServiceParsed(service);
                        service.add(createBundleDependency(b, dm, parser));
                        break;
                        
                    case ResourceDependency:
                        checkServiceParsed(service);
                        service.add(createResourceDependency(b, dm, parser));
                        break;
                }
            }

            List<Service> services = m_services.get(b);
            if (services == null)
            {
                services = new ArrayList<Service>();
                m_services.put(b, services);
            }
            services.add(service);
            dm.add(service);
        }
        catch (Throwable t)
        {
            m_logService.log(LogService.LOG_ERROR, "Error while parsing descriptor "
                + descriptorURL + " from bundle " + b.getSymbolicName(), t);
        }
        finally
        {
            if (in != null)
            {
                try
                {
                    in.close();
                }
                catch (IOException ignored)
                {
                }
            }
        }
    }

    /**
     * Unregisters all services for a stopping bundle.
     * @param b
     */
    private void bundleStopped(Bundle b)
    {
        m_logService.log(LogService.LOG_INFO, "bundle stopped: " + b.getSymbolicName());
        List<Service> services = m_services.remove(b);
        if (services != null)
        {
            for (Service s : services)
            {
                m_logService.log(LogService.LOG_INFO, "stopping service " + s);
                s.stop();
            }
        }
    }

    /**
     * Check if we have already parsed the Service entry from a given DM component descriptor file.
     * Each descriptor must start with a Service definition entry.
     * @param service the parsed service
     * @throws IllegalArgumentException if the service has not been parsed.
     */
    private void checkServiceParsed(Service service)
    {
        if (service == null)
        {
            throw new IllegalArgumentException("Service not declared in the first descriptor line");
        }
    }

    /**
     * Creates a Service that we parsed from a component descriptor entry.
     * @param b the bundle from where the service is started
     * @param dm the DependencyManager framework
     * @param parser the parser that just parsed the descriptor "Service" entry
     * @return the created DependencyManager Service
     * @throws ClassNotFoundException if the service implementation could not be instantiated
     */
    private Service createService(Bundle b, DependencyManager dm, DescriptorParser parser)
        throws ClassNotFoundException
    {
        Service service = dm.createService();
        // Get factory parameters.
        String factory = parser.getString(DescriptorParam.factory, null);
        String factoryMethod = parser.getString(DescriptorParam.factoryMethod, "create");

        if (factory == null)
        {
            // Set service impl
            String impl = parser.getString(DescriptorParam.impl);
            service.setImplementation(b.loadClass(impl));
        }
        else
        {
            // Set service factory
            Class<?> factoryClass = b.loadClass(factory);
            service.setFactory(factoryClass, factoryMethod);
        }

        // Set service callbacks
        setCommonServiceParams(service, parser);
        
        // Set service interface with associated service properties
        Dictionary<String, String> serviceProperties = parser.getDictionary(
            DescriptorParam.properties, null);
        String[] provides = parser.getStrings(DescriptorParam.provide, null);
        if (provides != null)
        {
            service.setInterface(provides, serviceProperties);
        }

        return service;
    }

    /**
     * Set common Service parameters, if provided from our Component descriptor
     * @param service
     * @param parser
     */
    private void setCommonServiceParams(Service service, DescriptorParser parser)
    {
        String init = parser.getString(DescriptorParam.init, null);
        String start = parser.getString(DescriptorParam.start, null);
        String stop = parser.getString(DescriptorParam.stop, null);
        String destroy = parser.getString(DescriptorParam.destroy, null);
        service.setCallbacks(init, start, stop, destroy);
        String composition = parser.getString(DescriptorParam.composition, null);
        if (composition != null)
        {
            service.setComposition(composition);
        }
    }

    /**
     * Creates an Aspect Service.
     * @param b
     * @param dm
     * @param parser
     * @return
     */
    private Service createAspectService(Bundle b, DependencyManager dm, DescriptorParser parser)
        throws ClassNotFoundException
    {
        Service service = null;

        Class<?> serviceInterface = b.loadClass(parser.getString(DescriptorParam.service));
        String serviceFilter = parser.getString(DescriptorParam.filter, null);
        Dictionary<String, String> aspectProperties = parser.getDictionary(DescriptorParam.properties, null);
        int ranking = parser.getInt(DescriptorParam.ranking, 1);          
        String factory = parser.getString(DescriptorParam.factory, null);
        if (factory == null)
        {
            String implClass = parser.getString(DescriptorParam.impl);
            Object impl = b.loadClass(implClass);
            service = dm.createAspectService(serviceInterface, serviceFilter, ranking, impl, aspectProperties);
        }
        else
        {
            String factoryMethod = parser.getString(DescriptorParam.factoryMethod, "create");
            Class<?> factoryClass = b.loadClass(factory);
            service = dm.createAspectService(serviceInterface, serviceFilter, ranking, factoryClass, factoryMethod, aspectProperties);
        }               
        setCommonServiceParams(service, parser);
        return service;
    }

    /**
     * Creates an Adapter Service.
     * @param b
     * @param dm
     * @param parser
     * @return
     */
    private Service createAdapterService(Bundle b, DependencyManager dm, DescriptorParser parser)
        throws ClassNotFoundException
    {
        Class<?> adapterImpl = b.loadClass(parser.getString(DescriptorParam.impl));
        String[] adapterService = parser.getStrings(DescriptorParam.adapterService, null);
        Dictionary<String, String> adapterProperties = parser.getDictionary(DescriptorParam.adapterProperties, null);
        Class<?> adapteeService = b.loadClass(parser.getString(DescriptorParam.adapteeService));
        String adapteeFilter = parser.getString(DescriptorParam.adapteeFilter, null);
     
        Service service = dm.createAdapterService(adapteeService, adapteeFilter, adapterService, adapterImpl, adapterProperties);
        setCommonServiceParams(service, parser);
        return service;
    }

    /**
     * Creates a Bundle Adapter Service.
     * @param b
     * @param dm
     * @param parser
     * @return
     */
    private Service createBundleAdapterService(Bundle b, DependencyManager dm, DescriptorParser parser)
        throws ClassNotFoundException
    {
        int stateMask = parser.getInt(DescriptorParam.stateMask, Bundle.INSTALLED | Bundle.RESOLVED | Bundle.ACTIVE);
        String filter = parser.getString(DescriptorParam.filter, null);
        Class<?> adapterImpl = b.loadClass(parser.getString(DescriptorParam.impl));
        String service = parser.getString(DescriptorParam.service, null);
        Dictionary<String, String> properties = parser.getDictionary(DescriptorParam.properties, null);
        boolean propagate = "true".equals(parser.getString(DescriptorParam.propagate, "false"));
        Service srv = dm.createBundleAdapterService(stateMask, filter, adapterImpl, service, properties, propagate);  
        setCommonServiceParams(srv, parser);
        return srv;
    }

    /**
     * Creates a Resource Adapter Service.
     * @param b
     * @param dm
     * @param parser
     * @return
     */
    private Service createResourceAdapterService(Bundle b, DependencyManager dm, DescriptorParser parser)
        throws ClassNotFoundException
    {
        String filter = parser.getString(DescriptorParam.filter, null);
        Class<?> impl = b.loadClass(parser.getString(DescriptorParam.impl));
        String service = parser.getString(DescriptorParam.service, null);
        Dictionary<String, String> properties = parser.getDictionary(DescriptorParam.properties, null);
        boolean propagate = "true".equals(parser.getString(DescriptorParam.propagate, "false"));
        Service srv = dm.createResourceAdapterService(filter, null, service, properties, impl, propagate);  
        setCommonServiceParams(srv, parser);
        return srv;
    }

    /**
     * Creates a Factory Configuration Adapter Service
     * @param b
     * @param dm
     * @param parser
     * @return
     */
    private Service createFactoryConfigurationAdapterService(Bundle b, DependencyManager dm, DescriptorParser parser)
        throws ClassNotFoundException
    {
        Class<?> impl = b.loadClass(parser.getString(DescriptorParam.impl));
        String factoryPid = parser.getString(DescriptorParam.factoryPid);
        String updated = parser.getString(DescriptorParam.updated);
        String[] services = parser.getStrings(DescriptorParam.service, null);
        Dictionary<String, String> properties = parser.getDictionary(DescriptorParam.properties, null);
        boolean propagate = "true".equals(parser.getString(DescriptorParam.propagate, "false"));
        Service srv = dm.createFactoryConfigurationAdapterService(factoryPid, updated, impl, services, properties, propagate);
        setCommonServiceParams(srv, parser);
        return srv;
    }


    /**
     * Creates a ServiceDependency that we parsed from a component descriptor "ServiceDependency" entry.
     * @param b
     * @param dm
     * @param parser
     * @param temporal true if this dependency is a temporal one, false if not.
     * @return
     * @throws ClassNotFoundException
     */
    private ServiceDependency createServiceDependency(Bundle b, DependencyManager dm,
        DescriptorParser parser, boolean temporal) throws ClassNotFoundException
    {
        ServiceDependency sd = temporal ? dm.createTemporalServiceDependency()
            : dm.createServiceDependency();

        // Set service with eventual service filter
        String service = parser.getString(DescriptorParam.service);
        Class serviceClass = b.loadClass(service);
        String serviceFilter = parser.getString(DescriptorParam.filter, null);
        sd.setService(serviceClass, serviceFilter);

        // Set default service impl
        String defaultServiceImpl = parser.getString(DescriptorParam.defaultImpl, null);
        if (defaultServiceImpl != null)
        {
            Class defaultServiceImplClass = b.loadClass(defaultServiceImpl);
            sd.setDefaultImplementation(defaultServiceImplClass);
        }

        // Set bind/unbind/rebind
        String added = parser.getString(DescriptorParam.added, null);
        String changed = temporal ? null : parser.getString(DescriptorParam.changed, null);
        String removed = temporal ? null : parser.getString(DescriptorParam.removed, null);
        sd.setCallbacks(added, changed, removed);

        // Set AutoConfig
        String autoConfigField = parser.getString(DescriptorParam.autoConfig, null);
        if (autoConfigField != null)
        {
            sd.setAutoConfig(autoConfigField);
        }

        // Do specific parsing for temporal service dependency
        if (temporal)
        {
            // Set the timeout value for a temporal service dependency
            String timeout = parser.getString(DescriptorParam.timeout, null);
            if (timeout != null)
            {
                ((TemporalServiceDependency) sd).setTimeout(Long.parseLong(timeout));
            }
            
            // Set required flag (always true for a temporal dependency)
            sd.setRequired(true);
        } else {
            // for ServiceDependency, get required flag.
            String required = parser.getString(DescriptorParam.required, "true");
            sd.setRequired("true".equals(required));
        }
        return sd;
    }

    /**
     * Creates a ConfigurationDependency that we parsed from a component descriptor entry.
     * @param b
     * @param dm
     * @param parser
     * @return
     */
    private Dependency createConfigurationDependency(Bundle b, DependencyManager dm,
        DescriptorParser parser)
    {
        ConfigurationDependency cd = dm.createConfigurationDependency();
        String pid = parser.getString(DescriptorParam.pid);
        if (pid == null)
        {
            throw new IllegalArgumentException(
                "pid attribute not provided in ConfigurationDependency declaration");
        }
        cd.setPid(pid);

        String propagate = parser.getString(DescriptorParam.propagate, "false");
        cd.setPropagate("true".equals(propagate));

        String callback = parser.getString(DescriptorParam.updated, "updated");
        cd.setCallback(callback);
        return cd;
    }
    
    /**
     * Creates a BundleDependency that we parsed from a component descriptor entry.
     * @param b
     * @param dm
     * @param parser
     * @return
     */
    private Dependency createBundleDependency(Bundle b, DependencyManager dm,
        DescriptorParser parser)
    {
        BundleDependency bd = dm.createBundleDependency();

        // Set add/changed/removed
        String added = parser.getString(DescriptorParam.added, null);
        String changed = parser.getString(DescriptorParam.changed, null);
        String removed = parser.getString(DescriptorParam.removed, null);
        bd.setCallbacks(added, changed, removed);

        // required
        bd.setRequired("true".equals(parser.getString(DescriptorParam.required, "true")));
        
        // filter
        String filter = parser.getString(DescriptorParam.filter, null);
        if (filter != null) 
        {
            bd.setFilter(filter);
        }
        
        // stateMask
        int stateMask = parser.getInt(DescriptorParam.stateMask, -1);
        if (stateMask != -1) 
        {
            bd.setStateMask(stateMask);
        }

        // propagate
        bd.setPropagate("true".equals(parser.getString(DescriptorParam.propagate, "false")));
        return bd;
    }

    private Dependency createResourceDependency(Bundle b, DependencyManager dm,
        DescriptorParser parser)
    {
        ResourceDependency rd = dm.createResourceDependency();

        // Set add/changed/removed
        String added = parser.getString(DescriptorParam.added, null);
        String changed = parser.getString(DescriptorParam.changed, null);
        String removed = parser.getString(DescriptorParam.removed, null);
        rd.setCallbacks(added, changed, removed);

        // required
        rd.setRequired("true".equals(parser.getString(DescriptorParam.required, "true")));
        
        // filter
        String filter = parser.getString(DescriptorParam.filter, null);
        if (filter != null) 
        {
            rd.setFilter(filter);
        }
        
        // propagate
        rd.setPropagate("true".equals(parser.getString(DescriptorParam.propagate, "false")));
        return rd;
    }
}
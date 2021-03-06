/*
 * Copyright 2015 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jboss.as.arquillian.container;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.jboss.arquillian.container.spi.Container;
import org.jboss.arquillian.container.spi.event.container.AfterUnDeploy;
import org.jboss.arquillian.container.spi.event.container.BeforeDeploy;
import org.jboss.arquillian.core.api.Instance;
import org.jboss.arquillian.core.api.annotation.Inject;
import org.jboss.arquillian.core.api.annotation.Observes;
import org.jboss.arquillian.test.spi.context.ClassContext;
import org.jboss.arquillian.test.spi.event.suite.AfterClass;
import org.jboss.as.arquillian.api.ServerSetup;
import org.jboss.as.arquillian.api.ServerSetupTask;
import org.jboss.logging.Logger;

/**
 * @author Stuart Douglas
 */
public class ServerSetupObserver {

    private static final Logger log = Logger.getLogger(ServerSetupObserver.class);

    @Inject
    private Instance<ManagementClient> managementClient;

    @Inject
    private Instance<ClassContext> classContextInstance;

    private final List<ServerSetupTask> setupTasksAll = new ArrayList<ServerSetupTask>();
    private final List<ServerSetupTask> setupTasksInForce = new ArrayList<ServerSetupTask>();
    private final Map<String, ManagementClient> active = new HashMap<String, ManagementClient>();
    private Map<String, Integer> deployed;
    boolean afterClassRun = false;

    public synchronized void handleBeforeDeployment(@Observes BeforeDeploy event, Container container) throws Throwable {

        if (deployed == null) {
            deployed = new HashMap<String, Integer>();
            setupTasksAll.clear();
            setupTasksInForce.clear();
            afterClassRun = false;
        }
        if (deployed.containsKey(container.getName())) {
            deployed.put(container.getName(), deployed.get(container.getName()) + 1);
        } else {
            deployed.put(container.getName(), 1);
        }

        if (active.containsKey(container.getName())) {
            return;
        }

        final ClassContext classContext = classContextInstance.get();
        if (classContext == null) {
            return;
        }

        final Class<?> currentClass = classContext.getActiveId();
        final ContainerClassHolder holder = new ContainerClassHolder(container.getName(), currentClass);

        ServerSetup setup = currentClass.getAnnotation(ServerSetup.class);
        if (setup == null) {
            return;
        }
        final Class<? extends ServerSetupTask>[] classes = setup.value();
        if (setupTasksAll.isEmpty()) {
            for (Class<? extends ServerSetupTask> clazz : classes) {
                Constructor<? extends ServerSetupTask> ctor = clazz.getDeclaredConstructor();
                ctor.setAccessible(true);
                setupTasksAll.add(ctor.newInstance());
            }
        } else {
            //this should never happen
            for (int i = 0; i < setupTasksAll.size(); ++i) {
                if (classes[i] != setupTasksAll.get(i).getClass()) {
                    throw new RuntimeException("Mismatched ServerSetupTask current is " + setupTasksAll + " but " + currentClass + " is expecting " + Arrays.asList(classes));
                }
            }
        }

        final ManagementClient client = managementClient.get();
        int index = 0;
        try {
            for (;index<setupTasksAll.size();index++) {
                final ServerSetupTask instance = setupTasksAll.get(index);
                setupTasksInForce.add(instance);
                instance.setup(client, container.getName());
            }
        } catch (Throwable e) {
            log.error("Setup task failed during setup. Offending class '"+setupTasksAll.get(index)+"'",e);
        }
        active.put(container.getName(), client);
    }

    public synchronized void afterTestClass(@Observes AfterClass afterClass) throws Exception {
        if (setupTasksInForce.isEmpty()) {
            return;
        }
        //clean up if there are no more deployments on the server
        //otherwise we clean up after the last deployment is removed
        final Iterator<Map.Entry<String, Integer>> it = deployed.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Integer> container = it.next();
            if (container.getValue() == 0) {
                if (active.containsKey(container.getKey())) {
                    ManagementClient client = active.get(container.getKey());
                    for (int i = setupTasksInForce.size() - 1; i >= 0; i--) {
                        try {
                            setupTasksInForce.get(i).tearDown(client, container.getKey());
                        } catch (Exception e) {
                            log.error("Setup task failed during tear down. Offending class '" + setupTasksAll.get(i) + "'", e);
                        }
                    }
                    active.remove(container.getKey());
                    it.remove();
                }
            }
        }
        afterClassRun = true;
        if (deployed.isEmpty()) {
            deployed = null;
            setupTasksAll.clear();
            setupTasksInForce.clear();
            afterClassRun = false;
        }
    }

    public synchronized void handleAfterUndeploy(@Observes AfterUnDeploy afterDeploy, final Container container) throws Exception {
        if(deployed == null) {
            return;
        }
        int count = deployed.get(container.getName());
        deployed.put(container.getName(), --count);
        if (count == 0 && afterClassRun) {
            for (int i = setupTasksInForce.size() - 1; i >= 0; i--) {
                try {
                    setupTasksInForce.get(i).tearDown(managementClient.get(), container.getName());
                } catch (Exception e) {
                    log.error("Setup task failed during tear down. Offending class '" + setupTasksAll.get(i) + "'", e);
                }
            }
            active.remove(container.getName());
            deployed.remove(container.getName());
        }
        if (deployed.isEmpty()) {
            deployed = null;
            setupTasksAll.clear();
            setupTasksInForce.clear();
            afterClassRun = false;
        }

    }


    private static final class ContainerClassHolder {
        private final Class<?> testClass;
        private final String name;

        private ContainerClassHolder(final String name, final Class<?> testClass) {
            this.name = name;
            this.testClass = testClass;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            final ContainerClassHolder that = (ContainerClassHolder) o;

            if (name != null ? !name.equals(that.name) : that.name != null) return false;
            if (testClass != null ? !testClass.equals(that.testClass) : that.testClass != null) return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = testClass != null ? testClass.hashCode() : 0;
            result = 31 * result + (name != null ? name.hashCode() : 0);
            return result;
        }
    }
}
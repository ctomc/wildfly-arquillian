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

package org.jboss.as.arquillian.service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.jboss.as.server.deployment.AttachmentKey;
import org.jboss.as.server.deployment.Attachments;
import org.jboss.as.server.deployment.DeploymentUnit;
import org.jboss.as.server.deployment.annotation.CompositeIndex;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.logging.Logger;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.StabilityMonitor;

/**
 * Uses the annotation index to check whether there is a class annotated
 * with JUnit @RunWith, or extending from the TestNG Arquillian runner.
 * In which case an {@link ArquillianConfig} service is created.
 *
 * @author Thomas.Diesler@jboss.com
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
public class ArquillianConfigBuilder {

    private static final Logger log = Logger.getLogger("org.jboss.as.arquillian");

    /*
     * Note: Do not put direct class references on JUnit or TestNG here; this
     * must be compatible with both without resulting in NCDFE
     *
     * AS7-1303
     */

    private static final String CLASS_NAME_JUNIT_RUNNER = "org.junit.runner.RunWith";

    private static final String CLASS_NAME_TESTNG_RUNNER = "org.jboss.arquillian.testng.Arquillian";

    private static final AttachmentKey<Set<String>> CLASSES = AttachmentKey.create(Set.class);

    ArquillianConfigBuilder(DeploymentUnit deploymentUnit) {
    }

    static ArquillianConfig processDeployment(ArquillianService arqService, DeploymentUnit depUnit) {

        // Get Test Class Names
        final Set<String> testClasses = depUnit.getAttachment(CLASSES);

        // No tests found
        if (testClasses == null || testClasses.isEmpty()) {
            return null;
        }

        // FIXME: Why do we get another service started event from a deployment INSTALLED service?
        ArquillianConfig arqConfig = new ArquillianConfig(arqService, depUnit, testClasses);
        ServiceController<?> service = arqService.getServiceContainer().getService(arqConfig.getServiceName());
        if (service != null) {
            service.setMode(ServiceController.Mode.REMOVE);
            final StabilityMonitor monitor = new StabilityMonitor();
            monitor.addController(service);
            try {
                monitor.awaitStability(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } finally {
                monitor.removeController(service);
            }
        }

        depUnit.putAttachment(ArquillianConfig.KEY, arqConfig);
        return arqConfig;
    }

    static void handleParseAnnotations(final DeploymentUnit deploymentUnit) {

        final CompositeIndex compositeIndex = deploymentUnit.getAttachment(Attachments.COMPOSITE_ANNOTATION_INDEX);
        if(compositeIndex == null) {
            log.warnf("Cannot find composite annotation index in: %s", deploymentUnit);
            return;
        }

        // Got JUnit?
        final DotName runWithName = DotName.createSimple(CLASS_NAME_JUNIT_RUNNER);
        final List<AnnotationInstance> runWithList = compositeIndex.getAnnotations(runWithName);

        // Got TestNG?
        final DotName testNGClassName = DotName.createSimple(CLASS_NAME_TESTNG_RUNNER);
        final Set<ClassInfo> testNgTests = compositeIndex.getAllKnownSubclasses(testNGClassName);

        // Get Test Class Names
        final Set<String> testClasses = new HashSet<String>();
        // JUnit
        for (AnnotationInstance instance : runWithList) {
            final AnnotationTarget target = instance.target();
            if (target instanceof ClassInfo) {
                final ClassInfo classInfo = (ClassInfo) target;
                final String testClassName = classInfo.name().toString();
                testClasses.add(testClassName);
            }
        }
        // TestNG
        for(final ClassInfo classInfo : testNgTests){
            testClasses.add(classInfo.name().toString());
        }
        deploymentUnit.putAttachment(CLASSES, testClasses);
    }
}

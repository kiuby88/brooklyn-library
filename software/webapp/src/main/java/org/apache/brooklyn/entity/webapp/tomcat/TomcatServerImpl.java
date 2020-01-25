/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.brooklyn.entity.webapp.tomcat;

import static java.lang.String.format;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.core.entity.EntityFunctions;
import org.apache.brooklyn.core.sensor.Sensors;
import org.apache.brooklyn.entity.java.JavaAppUtils;
import org.apache.brooklyn.entity.webapp.JavaWebAppSoftwareProcessImpl;
import org.apache.brooklyn.feed.function.FunctionFeed;
import org.apache.brooklyn.feed.function.FunctionPollConfig;
import org.apache.brooklyn.feed.jmx.JmxAttributePollConfig;
import org.apache.brooklyn.feed.jmx.JmxFeed;
import org.apache.brooklyn.location.cloudfoundry.CloudFoundryPaasLocation;
import org.apache.brooklyn.util.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Functions;
import com.google.common.base.Predicates;

/**
 * An {@link org.apache.brooklyn.api.entity.Entity} that represents a single Tomcat instance.
 */
public class TomcatServerImpl extends JavaWebAppSoftwareProcessImpl implements TomcatServer {

    private static final Logger LOG = LoggerFactory.getLogger(TomcatServerImpl.class);

    public TomcatServerImpl() {
        super();
    }

    private volatile JmxFeed jmxWebFeed;
    private volatile JmxFeed jmxAppFeed;

    private volatile FunctionFeed healtServiceProcessIsRunning;

    AttributeSensor<String> UPDATING_CLIENT = Sensors.newStringSensor("service.process.connected",
            "Whether the process for the service is confirmed as running");

    @Override
    public void connectSensors() {
        super.connectSensors();

        LOG.info("******Connect Sensors. Tomcat running without JMX monitoring; limited visibility of service available");
        if (getDriver().isJmxEnabled()) {
        LOG.info("******Connect Sensors. With JMX");
            String requestProcessorMbeanName = "Catalina:type=GlobalRequestProcessor,name=\"http-*\"";

            Integer port = isHttpsEnabled() ? getAttribute(HTTPS_PORT) : getAttribute(HTTP_PORT);
            String connectorMbeanName = format("Catalina:type=Connector,port=%s", port);
            boolean retrieveUsageMetrics = getConfig(RETRIEVE_USAGE_METRICS);

            jmxWebFeed = JmxFeed.builder()
                    .entity(this)
                    .period(3000, TimeUnit.MILLISECONDS)
                    .pollAttribute(new JmxAttributePollConfig<Boolean>(SERVICE_PROCESS_IS_RUNNING)
                            // TODO Want to use something different from SERVICE_PROCESS_IS_RUNNING,
                            // to indicate this is jmx MBean's reported state (or failure to connect)
                            .objectName(connectorMbeanName)
                            .attributeName("stateName")
                            .onSuccess(Functions.forPredicate(Predicates.<Object>equalTo("STARTED")))
                            .setOnFailureOrException(false)
                            .suppressDuplicates(true))
                    .pollAttribute(new JmxAttributePollConfig<String>(CONNECTOR_STATUS)
                            .objectName(connectorMbeanName)
                            .attributeName("stateName")
                            .suppressDuplicates(true))
                    .pollAttribute(new JmxAttributePollConfig<Integer>(ERROR_COUNT)
                            .objectName(requestProcessorMbeanName)
                            .attributeName("errorCount")
                            .enabled(retrieveUsageMetrics))
                    .pollAttribute(new JmxAttributePollConfig<Integer>(REQUEST_COUNT)
                            .objectName(requestProcessorMbeanName)
                            .attributeName("requestCount")
                            .enabled(retrieveUsageMetrics)
                            .onFailureOrException(EntityFunctions.attribute(this, REQUEST_COUNT)))
                    .pollAttribute(new JmxAttributePollConfig<Integer>(TOTAL_PROCESSING_TIME)
                            .objectName(requestProcessorMbeanName)
                            .attributeName("processingTime")
                            .enabled(retrieveUsageMetrics))
                    .build();

            jmxAppFeed = JavaAppUtils.connectMXBeanSensors(this);
        } else {
            // if not using JMX
            LOG.info("Tomcat running without JMX monitoring");
            connectServiceUpIsRunning();
            //Esto permite que la sesión del paas no se pierda.
            //SI ES COMPATIBLE CON LA MIGRACION
            connectPaasHealth();
        }
    }

    private void connectPaasHealth() {

        LOG.info("Iniciando connectPaasHealth");

        if (getDriver() != null && getDriver().getLocation() != null && getDriver().getLocation() instanceof CloudFoundryPaasLocation) {

            if (healtServiceProcessIsRunning != null) {
                healtServiceProcessIsRunning.stop();
            }

            final CloudFoundryPaasLocation location = (CloudFoundryPaasLocation) getDriver().getLocation();
            healtServiceProcessIsRunning = FunctionFeed.builder()
                    .entity(this)
                    .period(Duration.seconds(400))
                            //.period(Duration.seconds(400))
                    .onlyIfServiceUp(false)
                    .poll(new FunctionPollConfig<String, String>(UPDATING_CLIENT)
                            .suppressDuplicates(true)
                            .onException(Functions.<String>constant(null))
                            .callable(new Callable<String>() {
                                public String call() {
                                    LOG.info("============================>>>>>>>>>>>>> calling to refresh");
                                    location.getCloudFoundryClient().login();
                                    return null;
                                }
                            }))
                    .build();
        }
    }

    @Override
    public void disconnectSensors() {
        super.disconnectSensors();
        if (getDriver() != null && getDriver().isJmxEnabled()) {
            if (jmxWebFeed != null) jmxWebFeed.stop();
            if (jmxAppFeed != null) jmxAppFeed.stop();
        } else {
            disconnectServiceUpIsRunning();
        }
    }

    @SuppressWarnings("rawtypes")
    @Override
    public Class getDriverInterface() {
        return TomcatDriver.class;
    }

    @Override
    public String getShortName() {
        return "Tomcat";
    }

    public String getBuildpack() {
        return getConfig(TomcatServer.BUILDPACK);
    }

}


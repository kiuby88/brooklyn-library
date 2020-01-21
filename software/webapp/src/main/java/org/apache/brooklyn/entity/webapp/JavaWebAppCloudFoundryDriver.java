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
package org.apache.brooklyn.entity.webapp;

import static com.google.common.base.Preconditions.checkNotNull;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.brooklyn.api.entity.EntityLocal;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.entity.java.JavaSoftwareProcessDriver;
import org.apache.brooklyn.entity.software.base.AbstractApplicationCloudFoundryDriver;
import org.apache.brooklyn.entity.software.base.SoftwareProcess;
import org.apache.brooklyn.location.cloudfoundry.CloudFoundryPaasLocation;
import org.apache.brooklyn.util.collections.MutableSet;
import org.apache.brooklyn.util.http.HttpTool;
import org.cloudfoundry.client.lib.CloudFoundryException;
import org.cloudfoundry.client.lib.domain.CloudApplication;
import org.cloudfoundry.client.lib.domain.Staging;
import org.springframework.http.HttpStatus;

import com.google.common.annotations.Beta;
import com.google.common.collect.ImmutableSet;

@Beta
public abstract class JavaWebAppCloudFoundryDriver extends AbstractApplicationCloudFoundryDriver
        implements JavaSoftwareProcessDriver, JavaWebAppDriver {

    private static final int HTTP_PORT = 8080;
    private static final int HTTP_PORTS = 443;
    private static final Set<String> ENABLED_PROTOCOLS;

    static {
        ENABLED_PROTOCOLS = ImmutableSet.of("http", "https");
    }

    private String applicationName;
    private String applicationWarUrl;
    private Set<String> oldEnabledProtocols;

    public JavaWebAppCloudFoundryDriver(EntityLocal entity, CloudFoundryPaasLocation location) {
        super(entity, location);
    }

    public JavaWebAppSoftwareProcessImpl getEntity() {
        return (JavaWebAppSoftwareProcessImpl) super.getEntity();
    }

    @Override
    protected void init() {
        super.init();
        initApplicationParameters();
        initAttributes();
    }

    @SuppressWarnings("unchecked")
    /**
     * It allows to init parameters necessary for the drivers.
     */
    protected void initApplicationParameters() {
        //TODO: Probably, this method could be moved to the super class.
        //but, a new configkey should be necessary to specify the deployment
        //artifact (war) without using the java service.
        applicationWarUrl = getEntity().getConfig(JavaWebAppService.ROOT_WAR);

        String nameWithExtension = getFilenameContextMapper()
                .findArchiveNameFromUrl(applicationWarUrl, true);
        applicationName = nameWithExtension.substring(0, nameWithExtension.indexOf('.'))
                + "-" + entity.getId();

        //These values shouldn't be null or empty
        checkNotNull(applicationWarUrl, "application war url");
        checkNotNull(applicationName, "application name");
    }

    /**
     * It allows to change the value to the generic entity attributes
     * according to the PaaS constraint
     */
    protected void initAttributes() {

        oldEnabledProtocols = MutableSet
                .copyOf(getEntity().getConfig(WebAppServiceConstants.ENABLED_PROTOCOLS));
        getEntity().setAttribute(Attributes.HTTP_PORT, HTTP_PORT);
        getEntity().setAttribute(Attributes.HTTPS_PORT, HTTP_PORTS);

        getEntity().setAttribute(WebAppServiceConstants.ENABLED_PROTOCOLS, ENABLED_PROTOCOLS);
    }

    @Override
    public String getBuildpack() {
        return getEntity().getBuildpack();
    }

    protected String getApplicationUrl() {
        return applicationWarUrl;
    }

    public String getApplicationName() {
        return applicationName;
    }

    @Override
    public Set<String> getEnabledProtocols() {
        return entity.getAttribute(JavaWebAppSoftwareProcess.ENABLED_PROTOCOLS);
    }

    @Override
    public Integer getHttpPort() {
        return getEntity().getHttpPort();
    }

    @Override
    public Integer getHttpsPort() {
        return getEntity().getHttpsPort();
    }

    @Override
    public HttpsSslConfig getHttpsSslConfig() {
        return null;
    }

    @Override
    public boolean isRunning() {
        boolean result = false;
        boolean calculated = false;
        int i = 0;
        while ((i < 20) && (!calculated)) {
            try {
                result = super.isRunning() && isInferRootAvailable();
                calculated = true;
            } catch (CloudFoundryException e) {
                if (e.getStatusCode().equals(HttpStatus.NOT_FOUND)) {
                    throw e;
                } else {
                    //Error de acceso
                    i++;
                }
            } catch (Exception ee) {
                //Error de acceso, reintentando
                i++;
            }
        }
        if (i == 20) {
            throw new RuntimeException("Error checking the status of entity after 20 iterations");
        } else {
            return result;
        }
    }

    private boolean isInferRootAvailable() {
        boolean result;
        try {
            result = HttpTool.getHttpStatusCode(inferRootUrl()) == HttpURLConnection.HTTP_OK;
        } catch (Exception e) {
            result = false;
        }
        return result;
    }

    @Override
    public void preLaunch() {
        log.info("==============================  Calling PreLaunch");
        pushArtifact();
        configureEnv();
        log.info("==============================  COMPLETED: PreLaunch");
    }

    protected void pushArtifact() {
        log.info("==============================  Calling PushArtifact");
        File war;
        try {
            war = LocalResourcesDownloader.downloadResourceInLocalDir(getApplicationUrl());
            tryPush(getApplicationName(), war.getCanonicalPath());
        } catch (IOException e) {
            log.error("Error deploying application {} managed by driver {}",
                    new Object[]{getEntity(), this});
        }
    }

    private void tryPush(String name, String path) {

        log.info("==============================  Calling TRY PushArtifact");
        int i = 0;
        int limit = 20;
        while (i < limit)
            try {
                log.info("==============================  Uploading artifact");
                getClient().uploadApplication(name, path);
                return;
            } catch (Exception e) {
                log.error("Wrong {} updating artifact {}, {}. Because {}", new Object[]{ i,name, path, e.getCause().toString()});
                i++;
                if (i == limit) {
                    throw new RuntimeException("Error push artifact " + e);
                }
                refresh();
            }
    }

    /**
     * It adds {@link SoftwareProcess#SHELL_ENVIRONMENT} and
     * {@link JavaWebAppSoftwareProcess#JAVA_SYSPROPS) as envs on CF.
     */
    @SuppressWarnings("unchecked")
    protected void configureEnv() {
        log.info("==============================  Calling Configure Environemtn");
        Map<String, String> shellEnvProp = (Map<String, String>) (Map) getEntity().getConfig(SoftwareProcess.SHELL_ENVIRONMENT);
        setEnv(shellEnvProp);
        setEnv(getEntity().getConfig(JavaWebAppSoftwareProcess.JAVA_SYSPROPS));
    }

    @SuppressWarnings("unchecked")
    private void setEnv(Map<String, String> envs) {
        log.info("==============================  Calling Set environemtn of application");
        CloudApplication app = null;
        int i = 0;
        int limit = 20;
        while (i < limit)
            try {
                app = getClient().getApplication(applicationName);
                log.info("Application retrieved as expected");
                return;
            } catch (Exception e) {
                i++;
                if (i == limit) {
                    throw new RuntimeException("Error setting environment to application because application is not retrieved" + e);
                }
                refresh();
            }

        // app.setEnv() replaces the entire set of variables, so we need to add it externally.
        Map oldEnv = app.getEnvAsMap();
        oldEnv.putAll(envs);
        getClient().updateApplicationEnv(applicationName, oldEnv);
    }

    @Override
    public void postLaunch() {
        super.postLaunch();
        String domainUrl = inferRootUrl();
        getEntity().setAttribute(Attributes.MAIN_URI, URI.create(domainUrl));
        entity.setAttribute(WebAppService.ROOT_URL, domainUrl);
        entity.sensors().set(Attributes.ADDRESS, domainUrl);
    }

    protected String inferRootUrl() {
        //TODO: this method is copied from JavaWebAppSshDriver, so it could be moved to any super class. It could be moved to the entity too

        CloudApplication application = getClient().getApplication(getApplicationName());
        String domainUri = application.getUris().get(0);

        if (isProtocolEnabled("https")) {
            Integer port = getHttpsPort();
            checkNotNull(port, "HTTPS_PORT sensors not set; is an acceptable port available?");
            return String.format("https://%s", domainUri);
        } else if (isProtocolEnabled("http")) {
            Integer port = getHttpPort();
            checkNotNull(port, "HTTP_PORT sensors not set; is an acceptable port available?");
            return String.format("http://%s", domainUri);
        } else {
            throw new IllegalStateException("HTTP and HTTPS protocols not enabled for " + entity + "; enabled protocols are " + getEnabledProtocols());
        }
    }

    protected boolean isProtocolEnabled(String protocol) {
        //TODO: this method is copied from JavaWebAppSshDriver, so it could be moved to any super class. . It could be moved to the entity too
        Set<String> protocols = getEnabledProtocols();
        for (String contender : protocols) {
            if (protocol.equalsIgnoreCase(contender)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void deploy() {
        getEntity().deploy(applicationWarUrl, applicationName);
    }

    @Override
    public void deploy(File file) {
        deploy(file, null);
    }

    @Override
    public void deploy(File f, String targetName) {
        if (targetName == null) {
            targetName = f.getName();
        }
        deploy(f.toURI().toASCIIString(), targetName);
    }

    @Override
    public String deploy(String url, String targetName) {
        List<String> uris = new ArrayList<String>();
        Staging staging;
        staging = new Staging(null, getBuildpack());
        uris.add(inferApplicationDomainUri(getApplicationName()));

        log.info("======== DEPLOY INFO ========");
        log.info("ApplicationName: {}", getApplicationName());
        log.info("Staging: {}", staging);
        log.info("Location is null: {}", getLocation() == null);
        log.info("Location id: {}", getLocation().getId());
        log.info("Location memory: {}", getLocation().getConfig(CloudFoundryPaasLocation.REQUIRED_MEMORY));
        log.info("Uris : {}", uris);
        log.info("=============================");

        getClient().createApplication(getApplicationName(), staging,
                getLocation().getConfig(CloudFoundryPaasLocation.REQUIRED_MEMORY),
                uris, null);
        return targetName;
    }

    @Override
    public void undeploy(String targetName) {
        //TODO: complete
    }

    @Override
    public FilenameToWebContextMapper getFilenameContextMapper() {
        return new FilenameToWebContextMapper();
    }

    @Override
    public boolean isJmxEnabled() {
        return false;
    }


    @Override
    public void stop() {
        entity.sensors().set(JavaWebAppSoftwareProcess.ENABLED_PROTOCOLS, oldEnabledProtocols);
        super.stop();
    }

}

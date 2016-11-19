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


import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.apache.brooklyn.util.collections.MutableList;
import org.cloudfoundry.client.lib.CloudCredentials;
import org.cloudfoundry.client.lib.CloudFoundryClient;
import org.cloudfoundry.client.lib.domain.Staging;

public class Tempos {

    public static void main(String[] args) throws IOException {

        CloudCredentials credentials =
                new CloudCredentials("kiuby88.cloud@gmail.com", "spacemax");
        CloudFoundryClient client = new CloudFoundryClient(
                credentials,
                URI.create("https://api.run.pivotal.io").toURL(),
                "gsoc",
                "development",
                true);
        client.login();


        List<String> log = MutableList.of();
        //download
        long time_start, time_end;
        time_start = System.currentTimeMillis();
        File war = LocalResourcesDownloader
                .downloadResourceInLocalDir("http://search.maven.org/remotecontent?filepath=" +
                        "org/apache/brooklyn/example/brooklyn-example-hello-world-sql-webapp" +
                        "/0.8.0-incubating/brooklyn-example-hello-world-sql-webapp-0.8.0-" +
                        "incubating.war");
        time_end = System.currentTimeMillis();
        log.add("**************File ==> " + war.toString());
        log.add("**************the download has taken " + (time_end - time_start) + " milliseconds");


        //creation
        time_start = System.currentTimeMillis();
        List<String> uris = new ArrayList<String>();
        Staging staging;
        staging = new Staging(null, "https://github.com/cloudfoundry/java-buildpack.git");
        String name = "jijijiji";
        String defaultDomainName = client.getDefaultDomain().getName();
        uris.add(name + "-domain." + defaultDomainName);
        client.createApplication(name, staging, 512, uris, null);
        time_end = System.currentTimeMillis();
        log.add("**************the application creation has taken " + (time_end - time_start) + " milliseconds");



        //upload
        time_start = System.currentTimeMillis();
        client.uploadApplication(name, war.getCanonicalPath());
        time_end = System.currentTimeMillis();
        log.add("**************the upload has taken " + (time_end - time_start) + " milliseconds");

        System.out.println(log);

    }
}

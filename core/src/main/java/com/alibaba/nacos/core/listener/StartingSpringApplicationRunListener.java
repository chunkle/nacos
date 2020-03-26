/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.nacos.core.listener;

import com.alibaba.nacos.core.utils.DiskUtils;
import com.alibaba.nacos.core.utils.ApplicationUtils;
import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringApplicationRunListener;
import org.springframework.boot.context.event.EventPublishingRunListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

import static com.alibaba.nacos.core.utils.ApplicationUtils.LOCAL_IP;

/**
 * Logging starting message {@link SpringApplicationRunListener} before {@link EventPublishingRunListener} execution
 *
 * @author <a href="mailto:huangxiaoyu1018@gmail.com">hxy1991</a>
 * @since 0.5.0
 */
public class StartingSpringApplicationRunListener implements SpringApplicationRunListener, Ordered {

    private static final Logger LOGGER = LoggerFactory.getLogger(StartingSpringApplicationRunListener.class);

    private static final String MODE_PROPERTY_KEY_STAND_MODE = "nacos.mode";

    private static final String MODE_PROPERTY_KEY_FUNCTION_MODE = "nacos.function.mode";

    private static final String LOCAL_IP_PROPERTY_KEY = "nacos.local.ip";

    private ScheduledExecutorService scheduledExecutorService;

    private volatile boolean starting;

    public StartingSpringApplicationRunListener(SpringApplication application, String[] args) {

    }

    @Override
    public void starting() {
        starting = true;
    }

    @Override
    public void environmentPrepared(ConfigurableEnvironment environment) {
        ApplicationUtils.injectEnvironment(environment);
        if (ApplicationUtils.getStandaloneMode()) {
            System.setProperty(MODE_PROPERTY_KEY_STAND_MODE, "stand alone");
        } else {
            System.setProperty(MODE_PROPERTY_KEY_STAND_MODE, "cluster");
        }
        if (ApplicationUtils.getFunctionMode() == null) {
            System.setProperty(MODE_PROPERTY_KEY_FUNCTION_MODE, "All");
        } else if (ApplicationUtils.FUNCTION_MODE_CONFIG.equals(ApplicationUtils.getFunctionMode())) {
            System.setProperty(MODE_PROPERTY_KEY_FUNCTION_MODE, ApplicationUtils.FUNCTION_MODE_CONFIG);
        } else if (ApplicationUtils.FUNCTION_MODE_NAMING.equals(ApplicationUtils.getFunctionMode())) {
            System.setProperty(MODE_PROPERTY_KEY_FUNCTION_MODE, ApplicationUtils.FUNCTION_MODE_NAMING);
        }


        System.setProperty(LOCAL_IP_PROPERTY_KEY, LOCAL_IP);
    }

    @Override
    public void contextPrepared(ConfigurableApplicationContext context) {
        logClusterConf();
        logStarting();
    }

    @Override
    public void contextLoaded(ConfigurableApplicationContext context) {

    }

    @Override
    public void started(ConfigurableApplicationContext context) {
        starting = false;

        if (scheduledExecutorService != null) {
            scheduledExecutorService.shutdownNow();
        }

        logFilePath();

        LOGGER.info("Nacos started successfully in {} mode.", System.getProperty(MODE_PROPERTY_KEY_STAND_MODE));
    }

    @Override
    public void running(ConfigurableApplicationContext context) {

    }

    @Override
    public void failed(ConfigurableApplicationContext context, Throwable exception) {
        starting = false;

        logFilePath();

        LOGGER.error("Startup errors : {}", exception);

        LOGGER.error("Nacos failed to start, please see {}logs/nacos.log for more details.",
                ApplicationUtils.getNacosHome());
        context.close();
    }

    /**
     * Before {@link EventPublishingRunListener}
     *
     * @return HIGHEST_PRECEDENCE
     */
    @Override
    public int getOrder() {
        return HIGHEST_PRECEDENCE;
    }

    private void logClusterConf() {
        if (!ApplicationUtils.getStandaloneMode()) {
            try {
                List<String> clusterConf = ApplicationUtils.readClusterConf();
                LOGGER.info("The server IP list of Nacos is {}", clusterConf);
            } catch (IOException e) {
                LOGGER.error("read cluster conf fail", e);
            }
        }
    }

    private void logFilePath() {
        String[] dirNames = new String[]{"logs", "conf", "data"};
        for (String dirName : dirNames) {
            LOGGER.info("Nacos Log files: {}", Paths.get(ApplicationUtils.getNacosHome(), dirName).toString());
            try {
                DiskUtils.forceMkdir(new File(Paths.get(ApplicationUtils.getNacosHome(), dirName).toUri()));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void logStarting() {
        if (!ApplicationUtils.getStandaloneMode()) {

            scheduledExecutorService = new ScheduledThreadPoolExecutor(1, new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread thread = new Thread(r, "nacos-starting");
                    thread.setDaemon(true);
                    return thread;
                }
            });

            scheduledExecutorService.scheduleWithFixedDelay(new Runnable() {
                @Override
                public void run() {
                    if (starting) {
                        LOGGER.info("Nacos is starting...");
                    }
                }
            }, 1, 1, TimeUnit.SECONDS);
        }
    }
}

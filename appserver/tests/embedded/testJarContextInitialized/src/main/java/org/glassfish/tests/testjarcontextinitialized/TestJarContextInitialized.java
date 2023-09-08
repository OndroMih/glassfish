/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 */
package org.glassfish.tests.testjarcontextinitialized;

import static java.lang.System.Logger.Level.INFO;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;

/**
 *
 * @author ondro
 */
@WebListener
public class TestJarContextInitialized implements ServletContextListener {

    private static final System.Logger logger = System.getLogger(TestJarContextInitialized.class.getName());

    static int contextInitializedCounter = 0;

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        logger.log(INFO, "Listener initialized");
        contextInitializedCounter++;
    }
}

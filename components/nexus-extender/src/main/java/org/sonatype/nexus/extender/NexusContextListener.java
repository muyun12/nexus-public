/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2008-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.extender;

import java.lang.management.ManagementFactory;
import java.util.Dictionary;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.Filter;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

import com.codahale.metrics.SharedMetricRegistries;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Provider;
import com.google.inject.servlet.GuiceFilter;
import org.apache.karaf.features.Feature;
import org.apache.karaf.features.FeaturesService;
import org.apache.karaf.features.FeaturesService.Option;
import org.eclipse.sisu.bean.BeanManager;
import org.eclipse.sisu.inject.BeanLocator;
import org.eclipse.sisu.wire.ParameterKeys;
import org.eclipse.sisu.wire.WireModule;
import org.joda.time.Period;
import org.joda.time.format.PeriodFormat;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.SynchronousBundleListener;
import org.osgi.framework.startlevel.BundleStartLevel;
import org.osgi.framework.startlevel.FrameworkStartLevel;
import org.osgi.service.cm.ManagedService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Collections.singletonMap;
import static org.apache.karaf.features.FeaturesService.Option.NoAutoRefreshBundles;
import static org.apache.karaf.features.FeaturesService.Option.NoAutoRefreshManagedBundles;
import static org.sonatype.nexus.common.app.ManagedLifecycle.Phase.BOOT;
import static org.sonatype.nexus.common.app.ManagedLifecycle.Phase.LOGGING;
import static org.sonatype.nexus.common.app.ManagedLifecycle.Phase.SECURITY;
import static org.sonatype.nexus.common.app.ManagedLifecycle.Phase.TASKS;

/**
 * {@link ServletContextListener} that bootstraps the core Nexus application.
 *
 * @since 3.0
 */
public class NexusContextListener
    implements ServletContextListener, ManagedService, FrameworkListener
{
  /**
   * Start-level for the Nexus extender; this is distinct from the Karaf start-level (80).
   * It should be equal to 'org.osgi.framework.startlevel.beginning' in config.properties.
   */
  public static final int NEXUS_EXTENDER_START_LEVEL = 100;

  /**
   * Start-level at which point any additional Nexus plugins/features should be available.
   */
  public static final int NEXUS_PLUGIN_START_LEVEL = 200;

  static {
    boolean hasPaxExam;
    try {
      // detect if running with Pax-Exam so we can register our locator
      hasPaxExam = org.ops4j.pax.exam.util.Injector.class.isInterface();
    }
    catch (final LinkageError e) {
      hasPaxExam = false;
    }
    HAS_PAX_EXAM = hasPaxExam;
  }

  private static final boolean HAS_PAX_EXAM;

  private static final Logger log = LoggerFactory.getLogger(NexusContextListener.class);

  private final Map<Object, Object> nexusProperties = new ConcurrentHashMap<>(16, 0.75f, 1);

  private final NexusBundleExtender extender;

  private BundleContext bundleContext;

  private ServletContext servletContext;

  private FeaturesService featuresService;

  private Injector injector;

  private NexusLifecycleManager lifecycleManager;

  private ServiceRegistration<Filter> registration;

  public NexusContextListener(final NexusBundleExtender extender) {
    this.extender = checkNotNull(extender);
  }

  @Override
  public void contextInitialized(final ServletContextEvent event) {
    checkNotNull(event);

    SharedMetricRegistries.getOrCreate("nexus");

    bundleContext = extender.getBundleContext();

    servletContext = event.getServletContext();
    Map<?, ?> servletProperties = (Map<?, ?>) servletContext.getAttribute("org.sonatype.nexus.cfg");
    if (servletProperties == null) {
      servletProperties = System.getProperties();
    }
    nexusProperties.putAll(servletProperties);

    featuresService = bundleContext.getService(bundleContext.getServiceReference(FeaturesService.class));

    injector = Guice.createInjector(new WireModule( //
        new NexusContextModule(bundleContext, servletContext, nexusProperties)));

    extender.doStart(); // start tracking nexus bundles

    try {
      lifecycleManager = injector.getInstance(NexusLifecycleManager.class);

      lifecycleManager.to(LOGGING);

      // assign higher start level to any bundles installed after this point to hold back activation
      bundleContext.addBundleListener((SynchronousBundleListener) (e) -> {
        if (e.getType() == BundleEvent.INSTALLED) {
          e.getBundle().adapt(BundleStartLevel.class).setStartLevel(NEXUS_PLUGIN_START_LEVEL);
        }
      });

      // if we know what to install go ahead and continue activation then register filter when done
      String featureNames = (String) nexusProperties.get("nexus-features");
      if (!Strings.isNullOrEmpty(featureNames)) {
        installNexusFeatures(featureNames);
      }
      // otherwise just activate security and register the filter to support install/upgrade wizard
      else {
        lifecycleManager.to(SECURITY);
        registerNexusFilter();
      }
    }
    catch (final Exception e) {
      log.error("Failed to initialize context", e);
      Throwables.propagate(e);
    }
  }

  /**
   * Receives property changes from OSGi and updates the bound {@code nexusProperties}.
   */
  @Override
  public void updated(final Dictionary<String, ?> properties) {
    if (properties != null) {
      for (Enumeration<String> e = properties.keys(); e.hasMoreElements();) {
        final String key = e.nextElement();
        nexusProperties.put(key, properties.get(key));
      }
      System.getProperties().putAll(nexusProperties);
    }

    // post-wizard installation: apply the chosen configuration
    String featureNames = (String) nexusProperties.get("nexus-features");
    if (!Strings.isNullOrEmpty(featureNames) && lifecycleManager != null
        && lifecycleManager.getCurrentPhase() == SECURITY) {
      try {
        installNexusFeatures(featureNames);
      }
      catch (final Exception e) {
        log.error("Failed to update context", e);
        Throwables.propagate(e);
      }
    }
  }

  @Override
  @SuppressWarnings("finally")
  public void frameworkEvent(final FrameworkEvent event) {
    checkNotNull(event);

    if (event.getType() == FrameworkEvent.STARTLEVEL_CHANGED) {
      // feature bundles have all been activated at this point

      try {
        lifecycleManager.to(TASKS);
      }
      catch (final Exception e) {
        log.error("Failed to start nexus", e);
        if (!HAS_PAX_EXAM) {
          try {
            // force container to shutdown early
            bundleContext.getBundle(0).stop();
          }
          finally {
            throw Throwables.propagate(e); // NOSONAR
          }
        }
        // otherwise let Pax-Exam handle shutdown
      }

      registerNexusFilter();

      if (HAS_PAX_EXAM) {
        registerLocatorWithPaxExam(injector.getProvider(BeanLocator.class));
      }
    }
  }

  @Override
  public void contextDestroyed(final ServletContextEvent event) {
    // event is ignored, apparently can also be null

    // remove our dynamic filter
    if (registration != null) {
      registration.unregister();
      registration = null;
    }

    // log uptime before triggering activity which may run into problems
    long uptime = ManagementFactory.getRuntimeMXBean().getUptime();
    log.info("Uptime: {}", PeriodFormat.getDefault().print(new Period(uptime)));

    try {
      lifecycleManager.to(LOGGING);

      // dispose of JSR-250 components before logging goes
      injector.getInstance(BeanManager.class).unmanage();

      lifecycleManager.to(BOOT);
    }
    catch (final Exception e) {
      log.error("Failed to stop nexus", e);
    }

    extender.doStop(); // stop tracking bundles

    if (servletContext != null) {
      servletContext = null;
    }

    injector = null;

    SharedMetricRegistries.remove("nexus");
  }

  public Injector getInjector() {
    checkState(injector != null, "Missing injector reference");
    return injector;
  }

  /**
   * Install all features listed under "nexus-features".
   */
  private void installNexusFeatures(final String featureNames) throws Exception {
    final Set<Feature> features = new LinkedHashSet<>();

    for (final String name : Splitter.on(',').trimResults().omitEmptyStrings().split(featureNames)) {
      final Feature feature = featuresService.getFeature(name);
      if (feature != null) {
        features.add(feature);
      }
      else {
        log.warn("Missing: {}", name);
      }
    }

    log.info("Installing: {}", features);

    Set<String> featureIds = new HashSet<>(features.size());
    for (final Feature f : features) {
      // feature might already be installed in the cache; if so then skip installation
      if (!featuresService.isInstalled(f)) {
        featureIds.add(f.getId());
      }
    }

    if (!featureIds.isEmpty()) {
      // avoid auto-refreshing bundles as that could trigger unwanted restart/lifecycle events
      EnumSet<Option> options = EnumSet.of(NoAutoRefreshBundles, NoAutoRefreshManagedBundles);
      featuresService.installFeatures(featureIds, options);
    }

    log.info("Installed: {}", features);

    // feature bundles have all been installed, so raise framework start level to finish activation
    FrameworkStartLevel frameworkStartLevel = bundleContext.getBundle(0).adapt(FrameworkStartLevel.class);
    if (frameworkStartLevel.getStartLevel() < NEXUS_PLUGIN_START_LEVEL) {
      frameworkStartLevel.setStartLevel(NEXUS_PLUGIN_START_LEVEL, this);
      // activation continues asynchronously in frameworkEvent method...
    }
  }

  /**
   * Register our dynamic filter with the bootstrap listener.
   */
  private void registerNexusFilter() {
    if (registration == null) {
      final Filter filter = injector.getInstance(GuiceFilter.class);
      final Dictionary<String, ?> filterProperties = new Hashtable<>(singletonMap("name", "nexus"));
      registration = bundleContext.registerService(Filter.class, filter, filterProperties);
    }
  }

  /**
   * Registers our locator service with Pax-Exam to handle injection of test classes.
   */
  private void registerLocatorWithPaxExam(final Provider<BeanLocator> locatorProvider) {
    // ensure this service is ranked higher than the Pax-Exam one
    final Dictionary<String, Object> examProperties = new Hashtable<>();
    examProperties.put(Constants.SERVICE_RANKING, Integer.MAX_VALUE);
    examProperties.put("name", "nexus");

    bundleContext.registerService(org.ops4j.pax.exam.util.Injector.class, new org.ops4j.pax.exam.util.Injector()
    {
      @Override
      public void injectFields(final Object target) {
        Guice.createInjector(new WireModule(new AbstractModule()
        {
          @Override
          protected void configure() {
            // support injection of application components by wiring via shared locator
            // (use provider to avoid auto-publishing test-instance to the application)
            bind(BeanLocator.class).toProvider(locatorProvider);

            // support injection of application properties
            bind(ParameterKeys.PROPERTIES).toInstance(
                locatorProvider.get().locate(ParameterKeys.PROPERTIES).iterator().next().getValue());

            // inject the test-instance
            requestInjection(target);
          }
        }));
      }
    }, examProperties);
  }
}

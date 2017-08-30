/*******************************************************************************
 *  Copyright (c) 2017 SSI Schaefer IT Solutions GmbH and others.
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  which accompanies this distribution, and is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 *
 *  Contributors:
 *      SSI Schaefer IT Solutions GmbH
 *******************************************************************************/
package org.eclipse.tea.library.build.tasks.p2;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.e4.core.di.annotations.Execute;
import org.eclipse.equinox.internal.p2.artifact.repository.ArtifactRepositoryManager;
import org.eclipse.equinox.internal.p2.artifact.repository.simple.SimpleArtifactRepositoryFactory;
import org.eclipse.equinox.internal.p2.metadata.repository.MetadataRepositoryManager;
import org.eclipse.equinox.internal.p2.metadata.repository.SimpleMetadataRepositoryFactory;
import org.eclipse.equinox.internal.p2.updatesite.CategoryXMLAction;
import org.eclipse.equinox.p2.core.IProvisioningAgent;
import org.eclipse.equinox.p2.core.ProvisionException;
import org.eclipse.equinox.p2.metadata.Version;
import org.eclipse.equinox.p2.publisher.IPublisherAction;
import org.eclipse.equinox.p2.publisher.IPublisherInfo;
import org.eclipse.equinox.p2.publisher.Publisher;
import org.eclipse.equinox.p2.publisher.PublisherInfo;
import org.eclipse.equinox.p2.publisher.actions.IFeatureRootAdvice;
import org.eclipse.equinox.p2.publisher.actions.RootIUAction;
import org.eclipse.equinox.p2.publisher.eclipse.BundlesAction;
import org.eclipse.equinox.p2.publisher.eclipse.FeaturesAction;
import org.eclipse.equinox.p2.repository.IRepository;
import org.eclipse.equinox.p2.repository.IRepositoryManager;
import org.eclipse.equinox.p2.repository.artifact.IArtifactRepository;
import org.eclipse.equinox.p2.repository.metadata.IMetadataRepository;
import org.eclipse.pde.core.target.ITargetDefinition;
import org.eclipse.pde.core.target.ITargetLocation;
import org.eclipse.pde.internal.build.Utils;
import org.eclipse.tea.core.services.TaskingLog;
import org.eclipse.tea.library.build.config.BuildDirectories;
import org.eclipse.tea.library.build.internal.Activator;
import org.eclipse.tea.library.build.jar.JarManager;
import org.eclipse.tea.library.build.model.FeatureBuild;
import org.eclipse.tea.library.build.model.PlatformTriple;
import org.eclipse.tea.library.build.model.WorkspaceBuild;
import org.eclipse.tea.library.build.p2.TargetPlatformHelper;
import org.eclipse.tea.library.build.p2.TeaFeatureRootAdvice;
import org.eclipse.tea.library.build.p2.TeaProductAction;
import org.eclipse.tea.library.build.p2.TeaProductDescription;
import org.eclipse.tea.library.build.p2.UpdateSite;
import org.eclipse.tea.library.build.p2.UpdateSiteCategory;
import org.eclipse.tea.library.build.p2.UpdateSiteManager;
import org.eclipse.tea.library.build.tasks.jar.TaskRunFeaturePluginJarExport;
import org.eclipse.tea.library.build.util.FileUtils;

/**
 * Creates a new update site based on a product feature
 */
@SuppressWarnings("restriction")
public class TaskPublishProductUpdateSite {

	public static final String PRODUCT_VERSIONS_PROPERTIES = "productVersions.properties";

	/** the name of the update site */
	private final String siteName;

	/** The name of the feature to create the site */
	private final String productFeature;

	/** The name of the product file */
	private final String productFileName;

	private final boolean composite;

	/** the name of the feature containing the executables */
	private final static String EXECUTABLE = "org.eclipse.equinox.executable";

	/**
	 * Creates a new product publisher by using the given feature and product
	 *
	 * @param siteName
	 *            the name of the resulting update site
	 * @param productFeature
	 *            the id of the feature that contains the product file
	 * @param productFileName
	 *            the name of the product file (something like myApp.product)
	 * @param composite
	 *            whether the target site is a composite site. if it is, any
	 *            existing site at the target location is loaded and merged
	 *            into. otherwise the existing directory will be removed. if
	 *            composite is <code>true</code>, this task will <b>not</b>
	 *            create a ZIP file of the target directory.
	 */
	public TaskPublishProductUpdateSite(String siteName, String productFeature, String productFileName,
			boolean composite) {
		this.siteName = siteName;
		this.productFeature = productFeature;
		this.productFileName = productFileName;
		this.composite = composite;
	}

	@Override
	public String toString() {
		return "Publish Product Update Site (" + productFeature + ')';
	}

	@Execute
	public void run(TaskingLog log, UpdateSiteManager um, WorkspaceBuild wb, JarManager jarManager,
			BuildDirectories dirs) throws Exception {
		final File featureDir = new File(dirs.getOutputDirectory(),
				TaskRunFeaturePluginJarExport.getFeatureJarDirectory());
		final File pluginDir = new File(dirs.getOutputDirectory(),
				TaskRunFeaturePluginJarExport.getPluginJarDirectory());

		final UpdateSite site = um.getSite(siteName);

		final Set<File> featureLocations = new LinkedHashSet<>();
		final Set<File> pluginLocations = new LinkedHashSet<>();
		File deltaPack = null;

		featureLocations.add(featureDir);
		pluginLocations.add(pluginDir);

		ITargetDefinition targetDefinition = TargetPlatformHelper.getCurrentTargetDefinition();

		int fSize = featureLocations.size();
		for (ITargetLocation loc : targetDefinition.getTargetLocations()) {
			String location = loc.getLocation(true);
			File features = new File(location, "features");
			File plugins = new File(location, "plugins");

			if (!features.exists() || !plugins.exists()) {
				log.error(location + " does not seem to be valid. skipping.");
				continue;
			}

			// TODO: this is a SERIOUS problem, as the target platform might
			// contain much more
			// plugins in different versions which cause conflicts at the end.
			// This might happen if
			// there are updates to the target platform. Only completely
			// removing the directory on
			// disc and resetting the platform will solve this as long as this
			// code is not more
			// intelligent somehow.
			featureLocations.add(features);
			pluginLocations.add(plugins);

			if (looksLikeDeltaFeatures(features)) {
				deltaPack = features.getParentFile();
			}
		}

		if (deltaPack == null) {
			throw new IllegalStateException(
					"Cannot find delta-pack. Do you have the correct Target Platform activated?");
		}

		log.info("found " + (featureLocations.size() - fSize) + " locations from target platform");

		// ensure the feature is existing
		final FeatureBuild feature = wb.getFeature(productFeature);
		if (feature == null) {
			throw new RuntimeException("Cannot find feature " + productFeature + "'");
		}

		// ensure that the product file is existing
		final File productFile = new File(feature.getData().getBundleDir(), productFileName);
		if (!productFile.exists() || !productFile.isFile()) {
			throw new RuntimeException("Cannot find product file '" + productFileName + "'");
		}

		TeaProductDescription productDescriptor = new TeaProductDescription(productFile, feature);

		// create a JAR for the feature
		log.info("execJarCommand: " + feature.getFeatureName());
		jarManager.execJarCommands(feature, featureDir);
		final String featureVersion = feature.getData().getBundleVersion();
		final String buildVersion = jarManager.getQualifier();

		// write configuration to property file
		File propFile = new File(dirs.getSiteDirectory(), PRODUCT_VERSIONS_PROPERTIES);
		Properties properties = new Properties();
		properties.put("configs", PlatformTriple.getAllTargetsBuildPropStyle());
		properties.put(productDescriptor.getProductName(), buildVersion);
		properties.put(productDescriptor.getProductName() + ".release", featureVersion);
		properties.put(siteName, buildVersion);
		FeatureBuild.updateProperties(propFile, properties);

		// add actions to publish features and plug-ins to the target update
		// site
		log.info("Publish artifacts to update site '" + site.directory + "'");
		final List<IPublisherAction> actions = new ArrayList<>();
		actions.add(new FeaturesAction(featureLocations.toArray(new File[featureLocations.size()])));
		actions.add(new BundlesAction(pluginLocations.toArray(new File[pluginLocations.size()])));

		actions.add(new TeaProductAction(productDescriptor, getExecutablesDir(deltaPack)));
		actions.add(new RootIUAction(feature.getFeatureName(), Version.parseVersion(featureVersion),
				feature.getFeatureName()));

		// copy additional (dynamically computed) root files to the product
		addRootFiles(log);

		// create advice to publish additional resources
		final IPublisherInfo info = createPublisherInfo(log, site.directory);
		final IFeatureRootAdvice rootFileAdvice = createRootAdvice(feature, info);
		if (rootFileAdvice != null) {
			info.addAdvice(rootFileAdvice);
		}

		// create category file for the update site
		final File categoryFile = createCategoryFile(jarManager, wb, feature);
		actions.add(new CategoryXMLAction(categoryFile.toURI(), featureVersion));

		// publish the product to the update site
		final Publisher publisher = new Publisher(info);
		IStatus result = publisher.publish(actions.toArray(new IPublisherAction[0]), null);
		if (result.getSeverity() == IStatus.ERROR) {
			throw new RuntimeException("Failed to publish artifacts to update site '" + result + "'");
		}

		if (!composite) {
			// create a ZIP archive of the update site
			site.createUpdateSiteZip(jarManager.getZipExecFactory(), log);
		}
	}

	/**
	 * Checks whether a given feature location looks like a delta-pack. A delta
	 * pack has only a single feature starting with
	 * "org.eclipse.equinox.executable".
	 */
	private boolean looksLikeDeltaFeatures(File features) {
		File[] files = features.listFiles();

		for (File f : files) {
			if (f.getName().startsWith(EXECUTABLE)) {
				return true;
			}
		}

		return false;
	}

	/**
	 * Creates and returns an advice to publish additional files to the update
	 * site
	 */
	protected IFeatureRootAdvice createRootAdvice(FeatureBuild feature, IPublisherInfo info) throws Exception {
		// check if we have a build.properties that describes the files to
		// publish
		File propertyFile = new File(feature.getData().getBundleDir(), "build.properties");
		if (!propertyFile.exists() || !propertyFile.canRead()) {
			throw new RuntimeException("Unable to find '" + propertyFile + "'");
		}
		// populate all files defined in the given property file
		Properties properties = FileUtils.readProperties(propertyFile);
		TeaFeatureRootAdvice advice = new TeaFeatureRootAdvice(feature, info);
		advice.addFiles(Utils.processRootProperties(properties, false));
		return advice;
	}

	/**
	 * Creates and returns the category file to be used to categorize the
	 * content of the update site
	 */
	protected File createCategoryFile(JarManager jarManager, WorkspaceBuild wb, FeatureBuild feature) throws Exception {
		File propertyFile = new File(feature.getData().getBundleDir(), "wpob.properties");
		if (!propertyFile.exists() || !propertyFile.canRead()) {
			throw new RuntimeException("Unable to find '" + propertyFile + "'");
		}
		Properties properties = FileUtils.readProperties(propertyFile);
		String categoryName = properties.getProperty("category");
		if (categoryName == null || categoryName.isEmpty()) {
			throw new RuntimeException("Missing 'category' entry in '" + propertyFile + "'");
		}
		File dirName = BuildDirectories.get().getOutputDirectory();
		File categoryFile = new File(dirName, "category.xml");
		UpdateSiteCategory.generateCategoryXml(categoryFile,
				Collections.singletonMap(feature.getFeatureName(), "Default"), wb, jarManager);
		return categoryFile;
	}

	/**
	 * Computes and returns the location of the feature containing the
	 * executables
	 */
	protected File getExecutablesDir(File deltaPack) {
		File featuresDir = new File(deltaPack, "features");
		File feature = null;
		for (File candidate : featuresDir.listFiles()) {
			final String name = candidate.getName();
			if (!name.startsWith(EXECUTABLE)) {
				continue;
			}
			// remember the feature and go on
			if (feature == null) {
				feature = candidate;
				continue;
			}
			// take the feature with the highest version
			Version featureVersion = Version.create(feature.getName().substring(name.indexOf("_") + 1));
			Version candiateVersion = Version.create(name.substring(name.indexOf("_") + 1));
			if (featureVersion.compareTo(candiateVersion) < 1) {
				feature = candidate;
			}
		}
		if (feature == null) {
			throw new IllegalArgumentException(
					"Unable to locate executable feature '" + EXECUTABLE + "' in '" + deltaPack + "'");
		}
		return feature;
	}

	/**
	 * Copy files to the root-file directory before generation of the product.
	 * <p>
	 * Note: The additional files must be copied to a root-file-location that is
	 * configured in the build.properties of the product.
	 * </p>
	 *
	 * @param log
	 *            the log used for various status messages
	 * @throws Exception
	 *             if an error occurred while adding the files to the product
	 */
	protected void addRootFiles(TaskingLog log) throws Exception {
		// default: no additional files for this product
	}

	/**
	 * Creates and returns the metadata to be used by the publisher
	 *
	 * @param repositoryPath
	 *            the target repository to create
	 */
	protected IPublisherInfo createPublisherInfo(TaskingLog log, File repositoryPath) throws Exception {
		PublisherInfo info = new PublisherInfo();

		Map<String, String> properties = new TreeMap<>();
		properties.put(IRepository.PROP_COMPRESSED, "true");

		// Create the metadata repository.
		SimpleMetadataRepositoryFactory metadataRepositoryFactory = new SimpleMetadataRepositoryFactory();
		metadataRepositoryFactory.setAgent((IProvisioningAgent) Activator.getService(IProvisioningAgent.SERVICE_NAME));

		IMetadataRepository metadataRepository;

		try {
			metadataRepository = metadataRepositoryFactory.load(repositoryPath.toURI(),
					IRepositoryManager.REPOSITORY_HINT_MODIFIABLE, null);
			log.debug("loaded existing metadata repostiory at " + repositoryPath);
		} catch (ProvisionException e) {
			metadataRepository = metadataRepositoryFactory.create(repositoryPath.toURI(), "Metadata Repository",
					MetadataRepositoryManager.TYPE_SIMPLE_REPOSITORY, properties);
		}

		// Create the artifact repository.
		SimpleArtifactRepositoryFactory artifactRepositoryFactory = new SimpleArtifactRepositoryFactory();
		artifactRepositoryFactory.setAgent((IProvisioningAgent) Activator.getService(IProvisioningAgent.SERVICE_NAME));

		IArtifactRepository artifactRepository;
		try {
			artifactRepository = artifactRepositoryFactory.load(repositoryPath.toURI(),
					IRepositoryManager.REPOSITORY_HINT_MODIFIABLE, null);
			log.debug("loaded existing artifact repostiory at " + repositoryPath);
		} catch (ProvisionException e) {
			artifactRepository = artifactRepositoryFactory.create(repositoryPath.toURI(), "Artifact Repository",
					ArtifactRepositoryManager.TYPE_SIMPLE_REPOSITORY, properties);
		}

		info.setMetadataRepository(metadataRepository);
		info.setArtifactRepository(artifactRepository);
		info.setArtifactOptions(IPublisherInfo.A_PUBLISH | IPublisherInfo.A_INDEX | IPublisherInfo.A_NO_MD5);
		info.setConfigurations(
				new String[] { PlatformTriple.WIN32.toStringCmdLine(), PlatformTriple.WIN64.toStringCmdLine(),
						PlatformTriple.LINUX32.toStringCmdLine(), PlatformTriple.LINUX64.toStringCmdLine() });
		return info;
	}
}
/*******************************************************************************
 * Copyright (c) 2015, 2022 QNX Software Systems and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.cdt.cmake.core;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import org.eclipse.cdt.cmake.core.internal.Activator;
import org.eclipse.cdt.cmake.core.internal.CMakeConsoleWrapper;
import org.eclipse.cdt.cmake.core.internal.CMakeUtils;
import org.eclipse.cdt.cmake.core.internal.CommandDescriptorBuilder;
import org.eclipse.cdt.cmake.core.internal.CommandDescriptorBuilder.CommandDescriptor;
import org.eclipse.cdt.cmake.core.properties.CMakeGenerator;
import org.eclipse.cdt.cmake.core.properties.CMakePropertiesFactory;
import org.eclipse.cdt.cmake.core.properties.ICMakeGenerator;
import org.eclipse.cdt.cmake.core.properties.ICMakeProperties;
import org.eclipse.cdt.core.CommandLauncherManager;
import org.eclipse.cdt.core.ConsoleOutputStream;
import org.eclipse.cdt.core.ErrorParserManager;
import org.eclipse.cdt.core.IConsoleParser;
import org.eclipse.cdt.core.IMarkerGenerator;
import org.eclipse.cdt.core.ProblemMarkerInfo;
import org.eclipse.cdt.core.build.CBuildConfiguration;
import org.eclipse.cdt.core.build.IToolChain;
import org.eclipse.cdt.core.envvar.EnvironmentVariable;
import org.eclipse.cdt.core.envvar.IEnvironmentVariable;
import org.eclipse.cdt.core.model.ElementChangedEvent;
import org.eclipse.cdt.core.model.ICElementDelta;
import org.eclipse.cdt.core.model.ICModelMarker;
import org.eclipse.cdt.core.parser.ExtendedScannerInfo;
import org.eclipse.cdt.core.parser.IScannerInfo;
import org.eclipse.cdt.core.resources.IConsole;
import org.eclipse.cdt.jsoncdb.core.CompileCommandsJsonParser;
import org.eclipse.cdt.jsoncdb.core.ISourceFileInfoConsumer;
import org.eclipse.cdt.jsoncdb.core.ParseRequest;
import org.eclipse.cdt.utils.CommandLineUtil;
import org.eclipse.core.resources.IBuildConfiguration;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.launchbar.core.target.ILaunchTarget;

/**
 * @since 2.0
 */
public class CMakeBuildConfiguration extends CBuildConfiguration implements ICMakeBuildConfiguration {

	private ICMakeToolChainFile toolChainFile;

	private Map<IResource, IScannerInfo> infoPerResource;
	/**
	 * whether one of the CMakeLists.txt files in the project has been modified and saved by the
	 * user since the last build.<br>
	 * Cmake-generated build scripts re-run cmake if one of the CMakeLists.txt files was modified,
	 * but that output goes through ErrorParserManager and is impossible to parse because cmake
	 * outputs to both stderr and stdout and ErrorParserManager intermixes these streams making it
	 * impossible to parse for errors.<br>
	 * To work around that, we run cmake in advance with its dedicated working error parser.
	 */
	private boolean cmakeListsModified;

	public CMakeBuildConfiguration(IBuildConfiguration config, String name) throws CoreException {
		super(config, name);

		ICMakeToolChainManager manager = Activator.getService(ICMakeToolChainManager.class);
		toolChainFile = manager.getToolChainFileFor(getToolChain());
	}

	public CMakeBuildConfiguration(IBuildConfiguration config, String name, IToolChain toolChain,
			ICMakeToolChainFile toolChainFile, String launchMode, ILaunchTarget launchTarget) {
		super(config, name, toolChain, launchMode, launchTarget);
		this.toolChainFile = toolChainFile;
	}

	@Override
	public ICMakeProperties getCMakeProperties() {
		ICMakeProperties cmakeProperties = CMakePropertiesFactory.createProperties();

		String useDefaultCMakeSettings = getProperty(CMAKE_USE_DEFAULT_CMAKE_SETTINGS);
		final Map<String, String> properties;
		if (Boolean.parseBoolean(useDefaultCMakeSettings)) {
			properties = getDefaultProperties();
		} else {
			properties = getProperties();
		}

		String cmakeGenerator = properties.get(CMAKE_GENERATOR);
		if (cmakeGenerator != null) {
			CMakeGenerator generator = CMakeGenerator.getGenerator(cmakeGenerator);
			if (generator == null) {
				cmakeProperties.setGenerator(new CustomCMakeGenerator(cmakeGenerator));
			} else {
				cmakeProperties.setGenerator(generator);
			}
		}

		String extraArgs = properties.get(CMAKE_ARGUMENTS);
		List<String> extraArgsList = Arrays.asList(CommandLineUtil.argumentsToArray(extraArgs));
		cmakeProperties.setExtraArguments(extraArgsList);

		String buildCommand = properties.get(CMAKE_BUILD_COMMAND);
		if (buildCommand != null && !buildCommand.isBlank()) {
			cmakeProperties.setCommand(buildCommand);
		}

		String cleanTarget = properties.get(CMAKE_CLEAN_TARGET);
		if (cleanTarget != null && !cleanTarget.isBlank()) {
			cmakeProperties.setCleanTarget(cleanTarget);
		}

		String allTarget = properties.get(CMAKE_ALL_TARGET);
		if (allTarget != null && !allTarget.isBlank()) {
			cmakeProperties.setAllTarget(allTarget);
		}

		String buildType = properties.get(CMAKE_BUILD_TYPE);
		if (buildType == null || buildType.isBlank()) {
			buildType = getDefaultProperties().get(CMAKE_BUILD_TYPE);
		}
		cmakeProperties.setBuildType(buildType);

		return cmakeProperties;
	}

	/**
	 * Gets the tool-chain description file to pass to the cmake command-line.
	 *
	 * @return the tool-chain file or <code>null</code> if cmake should take the native (i.e. the
	 *         tools first found on the executable search path aka $path)
	 */
	public ICMakeToolChainFile getToolChainFile() {
		return toolChainFile;
	}

	@SuppressWarnings("unused") // kept for reference of the property names
	private boolean isLocal() throws CoreException {
		IToolChain toolchain = getToolChain();
		return (Platform.getOS().equals(toolchain.getProperty(IToolChain.ATTR_OS))
				|| "linux-container".equals(toolchain.getProperty(IToolChain.ATTR_OS))) //$NON-NLS-1$
				&& (Platform.getOSArch().equals(toolchain.getProperty(IToolChain.ATTR_ARCH)));
	}

	@Override
	public IProject[] build(int kind, Map<String, String> args, IConsole console, IProgressMonitor monitor)
			throws CoreException {
		IProject project = getProject();

		project.deleteMarkers(ICModelMarker.C_MODEL_PROBLEM_MARKER, false, IResource.DEPTH_INFINITE);

		try {

			ConsoleOutputStream infoStream = console.getInfoStream();

			Path buildDir = getBuildDirectory();

			boolean runCMake = cmakeListsModified;
			ICMakeProperties cmakeProperties = getCMakeProperties();

			runCMake |= !Files.exists(buildDir.resolve("CMakeCache.txt")); //$NON-NLS-1$

			if (!runCMake) {
				ICMakeGenerator generator = cmakeProperties.getGenerator();
				String makefileName = generator.getMakefileName();
				if (makefileName == null) {
					runCMake = true;
				} else {
					runCMake |= !Files.exists(buildDir.resolve(makefileName));
				}
			}
			CommandDescriptorBuilder cmdBuilder = new CommandDescriptorBuilder(cmakeProperties);
			if (runCMake) {
				CMakeBuildConfiguration.deleteCMakeErrorMarkers(project);

				infoStream.write(String.format(Messages.CMakeBuildConfiguration_Configuring, buildDir));
				CommandDescriptor command = cmdBuilder
						.makeCMakeCommandline(toolChainFile != null ? toolChainFile.getPath() : null);
				// tell cmake where its script is located..
				IContainer srcFolder = project;
				command.getArguments().add(new File(srcFolder.getLocationURI()).getAbsolutePath());

				infoStream.write(String.join(" ", command.getArguments()) + '\n'); //$NON-NLS-1$

				org.eclipse.core.runtime.Path workingDir = new org.eclipse.core.runtime.Path(
						getBuildDirectory().toString());
				// hook in cmake error parsing
				try (CMakeErrorParser errorParser = new CMakeErrorParser(new CMakeExecutionMarkerFactory(srcFolder))) {
					ParsingConsoleOutputStream errStream = new ParsingConsoleOutputStream(console.getErrorStream(),
							errorParser);
					IConsole errConsole = new CMakeConsoleWrapper(console, errStream);
					Process p = startBuildProcess(command.getArguments(), new IEnvironmentVariable[0], workingDir,
							errConsole, monitor);
					String arg0 = command.getArguments().get(0);
					if (p == null) {
						// process start failed
						String msg = String.format(Messages.CMakeBuildConfiguration_Failure, ""); //$NON-NLS-1$
						addMarker(new ProblemMarkerInfo(srcFolder.getProject(), -1, msg,
								IMarkerGenerator.SEVERITY_ERROR_BUILD, null, new org.eclipse.core.runtime.Path(arg0)));
						return null;
					}

					// check cmake exit status
					final int exitValue = watchProcess(errConsole, monitor);
					if (exitValue != 0) {
						// cmake had errors...
						String msg = String.format(Messages.CMakeBuildConfiguration_ExitFailure, arg0, exitValue);
						addMarker(srcFolder.getProject(), -1, msg, IMarkerGenerator.SEVERITY_ERROR_BUILD, null);
						return null;
					}
				}
				cmakeListsModified = false;
			}

			// parse compile_commands.json file
			getCompileCommandsFile().refreshLocal(IResource.DEPTH_ZERO, monitor);
			processCompileCommandsFile(console, monitor);

			infoStream.write(String.format(Messages.CMakeBuildConfiguration_BuildingIn, buildDir.toString()));
			// run the build tool...
			try (ErrorParserManager epm = new ErrorParserManager(project, getBuildDirectoryURI(), this,
					getToolChain().getErrorParserIds())) {
				epm.setOutputStream(console.getOutputStream());

				String envStr = getProperty(CMAKE_ENV);
				List<IEnvironmentVariable> envVars = new ArrayList<>();
				if (envStr != null) {
					List<String> envList = CMakeUtils.stripEnvVars(envStr);
					for (String s : envList) {
						int index = s.indexOf("="); //$NON-NLS-1$
						if (index == -1) {
							envVars.add(new EnvironmentVariable(s));
						} else {
							envVars.add(new EnvironmentVariable(s.substring(0, index), s.substring(index + 1)));
						}
					}
				}

				CommandDescriptor commandDescr = cmdBuilder.makeCMakeBuildCommandline(cmakeProperties.getAllTarget());
				List<String> command = commandDescr.getArguments();
				infoStream.write(String.join(" ", command) + '\n'); //$NON-NLS-1$

				org.eclipse.core.runtime.Path workingDir = new org.eclipse.core.runtime.Path(
						getBuildDirectory().toString());
				// TODO pass envvars from CommandDescriptor once we use ICommandLauncher
				Process p = startBuildProcess(command, envVars.toArray(new IEnvironmentVariable[0]), workingDir,
						console, monitor);
				if (p == null) {
					console.getErrorStream().write(String.format(Messages.CMakeBuildConfiguration_Failure, "")); //$NON-NLS-1$
					return null;
				}

				// check exit status
				final int exitValue = watchProcess(new IConsoleParser[] { epm }, monitor);
				if (exitValue != 0) {
					// had errors...
					String msg2 = String.format(Messages.CMakeBuildConfiguration_ExitFailure, command.get(0),
							exitValue);
					addMarker(project, -1, msg2, IMarkerGenerator.SEVERITY_ERROR_BUILD, null);
				}

				project.refreshLocal(IResource.DEPTH_INFINITE, monitor);

				infoStream.write(String.format(Messages.CMakeBuildConfiguration_BuildingComplete, epm.getErrorCount(),
						epm.getWarningCount(), buildDir.toString()));
			}

			return new IProject[] { project };
		} catch (IOException e) {
			throw new CoreException(Activator
					.errorStatus(String.format(Messages.CMakeBuildConfiguration_Building, project.getName()), e));
		}
	}

	@Override
	public void clean(IConsole console, IProgressMonitor monitor) throws CoreException {
		IProject project = getProject();
		try {

			project.deleteMarkers(ICModelMarker.C_MODEL_PROBLEM_MARKER, false, IResource.DEPTH_INFINITE);

			ICMakeProperties cmakeProperties = getCMakeProperties();
			CommandDescriptorBuilder cmdBuilder = new CommandDescriptorBuilder(cmakeProperties);
			CommandDescriptor command = cmdBuilder.makeCMakeBuildCommandline(cmakeProperties.getCleanTarget());
			ConsoleOutputStream infoStream = console.getInfoStream();

			Path buildDir = getBuildDirectory();

			if (!Files.exists(buildDir.resolve("CMakeFiles"))) { //$NON-NLS-1$
				infoStream.write(Messages.CMakeBuildConfiguration_NotFound);
				return;
			}

			infoStream.write(String.join(" ", command.getArguments()) + '\n'); //$NON-NLS-1$

			org.eclipse.core.runtime.Path workingDir = new org.eclipse.core.runtime.Path(
					getBuildDirectory().toString());
			Process p = startBuildProcess(command.getArguments(), new IEnvironmentVariable[0], workingDir, console,
					monitor);
			if (p == null) {
				// process start failed
				String msg = String.format(Messages.CMakeBuildConfiguration_Failure, ""); //$NON-NLS-1$
				addMarker(new ProblemMarkerInfo(project, -1, msg, IMarkerGenerator.SEVERITY_ERROR_BUILD, null,
						new org.eclipse.core.runtime.Path(command.getArguments().get(0))));
				return;
			}

			// check exit status
			final int exitValue = watchProcess(console, monitor);
			if (exitValue != 0) {
				// had errors...
				String msg = String.format(Messages.CMakeBuildConfiguration_ExitFailure, command.getArguments().get(0),
						exitValue);
				addMarker(project, -1, msg, IMarkerGenerator.SEVERITY_ERROR_BUILD, null);
			}

			infoStream.write(Messages.CMakeBuildConfiguration_BuildComplete);

			project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
		} catch (IOException e) {
			throw new CoreException(Activator
					.errorStatus(String.format(Messages.CMakeBuildConfiguration_Cleaning, project.getName()), e));
		}
	}

	private IFile getCompileCommandsFile() throws CoreException {
		return getBuildContainer().getFile(new org.eclipse.core.runtime.Path("compile_commands.json")); //$NON-NLS-1$
	}

	/**
	 * @param console the console to print the compiler output during built-ins detection to or
	 *                <code>null</code> if no separate console is to be allocated. Ignored if
	 *                workspace preferences indicate that no console output is wanted.
	 * @param monitor the job's progress monitor
	 */
	private void processCompileCommandsFile(IConsole console, IProgressMonitor monitor) throws CoreException {
		IFile file = getCompileCommandsFile();
		CompileCommandsJsonParser parser = new CompileCommandsJsonParser(
				new ParseRequest(file, new CMakeIndexerInfoConsumer(this::setScannerInformation),
						CommandLauncherManager.getInstance().getCommandLauncher(this), console));
		parser.parse(monitor);
	}

	/**
	 * Overridden since the ScannerInfoCache mechanism does not satisfy our needs.
	 */
	// interface IScannerInfoProvider
	@Override
	public IScannerInfo getScannerInformation(IResource resource) {
		if (infoPerResource == null) {
			// no build was run yet, nothing detected
			try {
				processCompileCommandsFile(null, new NullProgressMonitor());
			} catch (CoreException e) {
				Activator.log(e);
			}
		}
		return infoPerResource == null ? null : infoPerResource.get(resource);
	}

	private void setScannerInformation(Map<IResource, IScannerInfo> infoPerResource) {
		this.infoPerResource = infoPerResource;
	}

	/**
	 * Overwritten to detect whether one of the CMakeLists.txt files in the project was modified
	 * since the last build.
	 */
	@Override
	public void elementChanged(ElementChangedEvent event) {
		super.elementChanged(event);
		// Only respond to post change events
		if (event.getType() != ElementChangedEvent.POST_CHANGE)
			return;
		if (!cmakeListsModified) {
			processElementDelta(event.getDelta());
		}
	}

	/**
	 * Processes the delta in order to detect whether one of the CMakeLists.txt files in the project
	 * has been modified and saved by the user since the last build.
	 *
	 * @return <code>true</code> to continue with delta processing, otherwise <code>false</code>
	 */
	private boolean processElementDelta(ICElementDelta delta) {
		if (delta == null) {
			return true;
		}

		if (delta.getKind() == ICElementDelta.CHANGED) {
			// check for modified CMakeLists.txt file
			if (0 != (delta.getFlags() & ICElementDelta.F_CONTENT)) {
				IResourceDelta[] resourceDeltas = delta.getResourceDeltas();
				if (resourceDeltas != null) {
					for (IResourceDelta resourceDelta : resourceDeltas) {
						IResource resource = resourceDelta.getResource();
						if (resource.getType() == IResource.FILE) {
							String name = resource.getName();
							if (!resource.isDerived(IResource.CHECK_ANCESTORS)
									&& (name.equals("CMakeLists.txt") || name.endsWith(".cmake"))) { //$NON-NLS-1$ //$NON-NLS-2$
								cmakeListsModified = true;
								return false; // stop processing
							}
						}
					}
				}
			}
		}

		// recurse...
		for (ICElementDelta child : delta.getAffectedChildren()) {
			if (!processElementDelta(child)) {
				return false; // stop processing
			}
		}
		return true;
	}

	/**
	 * Overwritten since we do not parse console output to get scanner information.
	 */
	// interface IConsoleParser2
	@Override
	public boolean processLine(String line) {
		return true;
	}

	/**
	 * Overwritten since we do not parse console output to get scanner information.
	 */
	// interface IConsoleParser2
	@Override
	public boolean processLine(String line, List<Job> jobsArray) {
		return true;
	}

	/**
	 * Overwritten since we do not parse console output to get scanner information.
	 */
	// interface IConsoleParser2
	@Override
	public void shutdown() {
	}

	/**
	 * Deletes all CMake error markers on the specified project.
	 *
	 * @param project the project where to remove the error markers.
	 * @throws CoreException
	 */
	private static void deleteCMakeErrorMarkers(IProject project) throws CoreException {
		project.deleteMarkers(ICMakeExecutionMarkerFactory.CMAKE_PROBLEM_MARKER_ID, false, IResource.DEPTH_INFINITE);
	}

	/**
	 * For cases when a generator is not one of those built-in to CDT we use this
	 * custom generator. Unlike the built-in generators this one does not know
	 * what the generator will use as a makefile name, so the build need to run
	 * the generation stage with each build instead of optimizing it. See
	 * {@link CMakeBuildConfiguration#build(int, Map, IConsole, IProgressMonitor)}
	 */
	private static final class CustomCMakeGenerator implements ICMakeGenerator {
		private final String cmakeGenerator;

		private CustomCMakeGenerator(String cmakeGenerator) {
			this.cmakeGenerator = cmakeGenerator;
		}

		@Override
		public String getMakefileName() {
			return null;
		}

		@Override
		public String getIgnoreErrOption() {
			return null;
		}

		@Override
		public String getCMakeName() {
			return cmakeGenerator;
		}
	}

	private static class CMakeIndexerInfoConsumer implements ISourceFileInfoConsumer {
		/**
		 * gathered IScannerInfo objects or <code>null</code> if no new IScannerInfo was received
		 */
		private Map<IResource, IScannerInfo> infoPerResource = new HashMap<>();
		private boolean haveUpdates;
		private final Consumer<Map<IResource, IScannerInfo>> resultSetter;

		/**
		 * @param resultSetter receives the all scanner information when processing is finished
		 */
		public CMakeIndexerInfoConsumer(Consumer<Map<IResource, IScannerInfo>> resultSetter) {
			this.resultSetter = Objects.requireNonNull(resultSetter);
		}

		@Override
		public void acceptSourceFileInfo(String sourceFileName, List<String> systemIncludePaths,
				Map<String, String> definedSymbols, List<String> includePaths, List<String> macroFiles,
				List<String> includeFiles) {
			IFile file = getFileForCMakePath(sourceFileName);
			if (file != null) {
				ExtendedScannerInfo info = new ExtendedScannerInfo(definedSymbols,
						systemIncludePaths.stream().toArray(String[]::new), macroFiles.stream().toArray(String[]::new),
						includeFiles.stream().toArray(String[]::new), includePaths.stream().toArray(String[]::new));
				infoPerResource.put(file, info);
				haveUpdates = true;
			}
		}

		/**
		 * Gets an IFile object that corresponds to the source file name given in CMake notation.
		 *
		 * @param sourceFileName the name of the source file, in CMake notation. Note that on
		 *                       windows, CMake writes filenames with forward slashes (/) such as
		 *                       {@code H://path//to//source.c}.
		 * @return a IFile object or <code>null</code>
		 */
		private IFile getFileForCMakePath(String sourceFileName) {
			org.eclipse.core.runtime.Path path = new org.eclipse.core.runtime.Path(sourceFileName);
			IFile file = ResourcesPlugin.getWorkspace().getRoot().getFileForLocation(path);
			// TODO maybe we need to introduce a strategy here to get the workbench resource
			// Possible build scenarios:
			// 1) linux native: should be OK as is
			// 2) linux host, building in container: should be OK as is
			// 3) windows native: Path.fromOSString()?
			// 4) windows host, building in linux container: ??? needs testing on windows
			return file;
		}

		@Override
		public void shutdown() {
			if (haveUpdates) {
				// we received updates
				resultSetter.accept(infoPerResource);
				infoPerResource = null;
				haveUpdates = false;
			}
		}
	} // CMakeIndexerInfoConsumer

	@Override
	public Map<String, String> getDefaultProperties() {
		return Map.of(//
				CMAKE_GENERATOR, CMAKE_GENERATOR_DEFAULT, //
				CMAKE_USE_DEFAULT_CMAKE_SETTINGS, CMAKE_USE_DEFAULT_CMAKE_SETTINGS_DEFAULT, //
				CMAKE_ARGUMENTS, CMAKE_ARGUMENTS_DEFAULT, //
				CMAKE_BUILD_COMMAND, CMAKE_BUILD_COMMAND_DEFAULT, //
				CMAKE_ALL_TARGET, CMAKE_ALL_TARGET_DEFAULT, //
				CMAKE_CLEAN_TARGET, CMAKE_CLEAN_TARGET_DEFAULT, //
				CMAKE_BUILD_TYPE, ILaunchManager.DEBUG_MODE.equals(getLaunchMode()) ? "Debug" : "Release" //$NON-NLS-1$ //$NON-NLS-2$
		);
	}

	@Override
	public Map<String, String> getProperties() {
		var map = new HashMap<String, String>();
		map.putAll(getDefaultProperties());
		map.putAll(super.getProperties());
		return map;
	}

	@Override
	public String getProperty(String name) {
		return getSettings().get(name, getDefaultProperties().get(name));
	}
}

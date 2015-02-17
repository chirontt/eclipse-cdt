/*******************************************************************************
 * Copyright (c) 2012, 2015 Wind River Systems, Inc. and others. All rights reserved.
 * This program and the accompanying materials are made available under the terms
 * of the Eclipse Public License v1.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 * Wind River Systems - initial API and implementation
 *******************************************************************************/
package org.eclipse.tcf.te.ui.terminals.local.launcher;

import java.io.File;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.eclipse.cdt.utils.pty.PTY;
import org.eclipse.core.runtime.Assert;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.URIUtil;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.tcf.te.core.terminals.TerminalServiceFactory;
import org.eclipse.tcf.te.core.terminals.interfaces.ITerminalService;
import org.eclipse.tcf.te.core.terminals.interfaces.ITerminalService.Done;
import org.eclipse.tcf.te.core.terminals.interfaces.ITerminalServiceOutputStreamMonitorListener;
import org.eclipse.tcf.te.core.terminals.interfaces.constants.ILineSeparatorConstants;
import org.eclipse.tcf.te.core.terminals.interfaces.constants.ITerminalsConnectorConstants;
import org.eclipse.tcf.te.ui.terminals.interfaces.IConfigurationPanel;
import org.eclipse.tcf.te.ui.terminals.interfaces.IConfigurationPanelContainer;
import org.eclipse.tcf.te.ui.terminals.interfaces.IMementoHandler;
import org.eclipse.tcf.te.ui.terminals.internal.SettingsStore;
import org.eclipse.tcf.te.ui.terminals.launcher.AbstractLauncherDelegate;
import org.eclipse.tcf.te.ui.terminals.local.activator.UIPlugin;
import org.eclipse.tcf.te.ui.terminals.local.controls.LocalWizardConfigurationPanel;
import org.eclipse.tcf.te.ui.terminals.local.showin.interfaces.IPreferenceKeys;
import org.eclipse.tcf.te.ui.terminals.process.ProcessSettings;
import org.eclipse.tm.internal.terminal.provisional.api.ISettingsStore;
import org.eclipse.tm.internal.terminal.provisional.api.ITerminalConnector;
import org.eclipse.tm.internal.terminal.provisional.api.TerminalConnectorExtension;
import org.eclipse.ui.ISelectionService;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.WorkbenchEncoding;
import org.osgi.framework.Bundle;

/**
 * Serial launcher delegate implementation.
 */
@SuppressWarnings("restriction")
public class LocalLauncherDelegate extends AbstractLauncherDelegate {

	private final IMementoHandler mementoHandler = new LocalMementoHandler();

	/* (non-Javadoc)
	 * @see org.eclipse.tcf.te.ui.terminals.interfaces.ILauncherDelegate#needsUserConfiguration()
	 */
	@Override
	public boolean needsUserConfiguration() {
		return false;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.tcf.te.ui.terminals.interfaces.ILauncherDelegate#getPanel(org.eclipse.tcf.te.ui.terminals.interfaces.IConfigurationPanelContainer)
	 */
	@Override
	public IConfigurationPanel getPanel(IConfigurationPanelContainer container) {
		return new LocalWizardConfigurationPanel(container);
	}

	/* (non-Javadoc)
	 * @see org.eclipse.tcf.te.ui.terminals.interfaces.ILauncherDelegate#execute(java.util.Map, org.eclipse.tcf.te.core.terminals.interfaces.ITerminalService.Done)
	 */
	@Override
	public void execute(Map<String, Object> properties, Done done) {
		Assert.isNotNull(properties);

		// Set the terminal tab title
		String terminalTitle = getTerminalTitle(properties);
		if (terminalTitle != null) {
			properties.put(ITerminalsConnectorConstants.PROP_TITLE, terminalTitle);
		}

		// If not configured, set the default encodings for the local terminal
		if (!properties.containsKey(ITerminalsConnectorConstants.PROP_ENCODING)) {
			String encoding = null;
			// Set the default encoding:
			//     Default UTF-8 on Mac or Windows for Local, Preferences:Platform encoding otherwise
			if (Platform.OS_MACOSX.equals(Platform.getOS()) || Platform.OS_WIN32.equals(Platform.getOS())) {
				encoding = "UTF-8"; //$NON-NLS-1$
			} else {
				encoding = WorkbenchEncoding.getWorkbenchDefaultEncoding();
			}
			if (encoding != null && !"".equals(encoding)) properties.put(ITerminalsConnectorConstants.PROP_ENCODING, encoding); //$NON-NLS-1$
		}

		// For local terminals, force a new terminal tab each time it is launched,
		// if not set otherwise from outside
		if (!properties.containsKey(ITerminalsConnectorConstants.PROP_FORCE_NEW)) {
			properties.put(ITerminalsConnectorConstants.PROP_FORCE_NEW, Boolean.TRUE);
		}

		// Initialize the local terminal working directory.
		// By default, start the local terminal in the users home directory
		String initialCwd = UIPlugin.getScopedPreferences().getString(IPreferenceKeys.PREF_LOCAL_TERMINAL_INITIAL_CWD);
		String cwd = null;
		if (initialCwd == null || IPreferenceKeys.PREF_INITIAL_CWD_USER_HOME.equals(initialCwd) || "".equals(initialCwd.trim())) { //$NON-NLS-1$
			cwd = System.getProperty("user.home"); //$NON-NLS-1$
		} else if (IPreferenceKeys.PREF_INITIAL_CWD_ECLIPSE_HOME.equals(initialCwd)) {
			String eclipseHomeLocation = System.getProperty("eclipse.home.location"); //$NON-NLS-1$
			if (eclipseHomeLocation != null) {
				try {
					URI uri = URIUtil.fromString(eclipseHomeLocation);
					File f = URIUtil.toFile(uri);
					cwd = f.getAbsolutePath();
				} catch (URISyntaxException ex) { /* ignored on purpose */ }
			}
		} else if (IPreferenceKeys.PREF_INITIAL_CWD_ECLIPSE_WS.equals(initialCwd)) {
			Bundle bundle = Platform.getBundle("org.eclipse.core.resources"); //$NON-NLS-1$
			if (bundle != null && (bundle.getState() == Bundle.RESOLVED || bundle.getState() == Bundle.ACTIVE)) {
		        if (org.eclipse.core.resources.ResourcesPlugin.getWorkspace() != null
		        	            && org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot() != null
		        	            && org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot().getLocation() != null) {
		        	cwd = org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot().getLocation().toOSString();
		        }
			}
		} else {
			IPath p = new Path(initialCwd);
			if (p.toFile().canRead() && p.toFile().isDirectory()) {
				cwd = p.toOSString();
			}
		}

		if (cwd != null && !"".equals(cwd)) { //$NON-NLS-1$
			properties.put(ITerminalsConnectorConstants.PROP_PROCESS_WORKING_DIR, cwd);
		}

		// If the current selection resolved to an folder, default the working directory
		// to that folder and update the terminal title
		ISelectionService service = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getSelectionService();
		if ((service != null && service.getSelection() != null) || properties.containsKey(ITerminalsConnectorConstants.PROP_SELECTION)) {
			ISelection selection = (ISelection)properties.get(ITerminalsConnectorConstants.PROP_SELECTION);
			if (selection == null) selection = service.getSelection();
			if (selection instanceof IStructuredSelection && !selection.isEmpty()) {
				String dir = null;
				Iterator<?> iter = ((IStructuredSelection)selection).iterator();
				while (iter.hasNext()) {
					Object element = iter.next();

					Bundle bundle = Platform.getBundle("org.eclipse.core.resources"); //$NON-NLS-1$
					if (bundle != null && (bundle.getState() == Bundle.RESOLVED || bundle.getState() == Bundle.ACTIVE)) {
						// If the element is not an IResource, try to adapt to IResource
						if (!(element instanceof org.eclipse.core.resources.IResource)) {
							Object adapted = element instanceof IAdaptable ? ((IAdaptable)element).getAdapter(org.eclipse.core.resources.IResource.class) : null;
							if (adapted == null) adapted = Platform.getAdapterManager().getAdapter(element, org.eclipse.core.resources.IResource.class);
							if (adapted != null) element = adapted;
						}

						if (element instanceof org.eclipse.core.resources.IResource && ((org.eclipse.core.resources.IResource)element).exists()) {
							IPath location = ((org.eclipse.core.resources.IResource)element).getLocation();
							if (location == null) continue;
							if (location.toFile().isFile()) location = location.removeLastSegments(1);
							if (location.toFile().isDirectory() && location.toFile().canRead()) {
								dir = location.toFile().getAbsolutePath();
								break;
							}
						}
					}
				}
				if (dir != null) {
					properties.put(ITerminalsConnectorConstants.PROP_PROCESS_WORKING_DIR, dir);

					String basename = new Path(dir).lastSegment();
					properties.put(ITerminalsConnectorConstants.PROP_TITLE, basename + " (" + terminalTitle + ")"); //$NON-NLS-1$ //$NON-NLS-2$
				}
			}
		}

		// Get the terminal service
		ITerminalService terminal = TerminalServiceFactory.getService();
		// If not available, we cannot fulfill this request
		if (terminal != null) {
			terminal.openConsole(properties, done);
		}
	}

	/**
	 * Returns the terminal title string.
	 * <p>
	 * The default implementation constructs a title like &quot;Serial &lt;port&gt; (Start time) &quot;.
	 *
	 * @return The terminal title string or <code>null</code>.
	 */
	private String getTerminalTitle(Map<String, Object> properties) {
		try {
			String hostname = InetAddress.getLocalHost().getHostName();
			if (hostname != null && !"".equals(hostname.trim())) { //$NON-NLS-1$
				return hostname;
			}
		} catch (UnknownHostException e) { /* ignored on purpose */ }
		return "Local"; //$NON-NLS-1$
	}

	/* (non-Javadoc)
	 * @see org.eclipse.core.runtime.PlatformObject#getAdapter(java.lang.Class)
	 */
	@Override
	public Object getAdapter(Class adapter) {
		if (IMementoHandler.class.equals(adapter)) {
			return mementoHandler;
		}
	    return super.getAdapter(adapter);
	}

	/**
	 * Returns the default shell to launch. Looks at the environment
	 * variable "SHELL" first before assuming some default default values.
	 *
	 * @return The default shell to launch.
	 */
	private final File defaultShell() {
		String shell = null;
		if (Platform.OS_WIN32.equals(Platform.getOS())) {
			if (System.getenv("ComSpec") != null && !"".equals(System.getenv("ComSpec").trim())) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				shell = System.getenv("ComSpec").trim(); //$NON-NLS-1$
			} else {
				shell = "cmd.exe"; //$NON-NLS-1$
			}
		}
		if (shell == null) {
			if (System.getenv("SHELL") != null && !"".equals(System.getenv("SHELL").trim())) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				shell = System.getenv("SHELL").trim(); //$NON-NLS-1$
			} else {
				shell = "/bin/sh"; //$NON-NLS-1$
			}
		}

		return new File(shell);
	}

	/* (non-Javadoc)
	 * @see org.eclipse.tcf.te.ui.terminals.interfaces.ILauncherDelegate#createTerminalConnector(java.util.Map)
	 */
    @Override
	public ITerminalConnector createTerminalConnector(Map<String, Object> properties) {
		Assert.isNotNull(properties);

		// Check for the terminal connector id
		String connectorId = (String)properties.get(ITerminalsConnectorConstants.PROP_TERMINAL_CONNECTOR_ID);
		if (connectorId == null) connectorId = "org.eclipse.tcf.te.ui.terminals.local.LocalConnector"; //$NON-NLS-1$

		// Extract the process properties using defaults
		String image;
		if (!properties.containsKey(ITerminalsConnectorConstants.PROP_PROCESS_PATH)
				|| properties.get(ITerminalsConnectorConstants.PROP_PROCESS_PATH) == null) {
			File defaultShell = defaultShell();
			image = defaultShell.isAbsolute() ? defaultShell.getAbsolutePath() : defaultShell.getPath();
		} else {
			image = (String)properties.get(ITerminalsConnectorConstants.PROP_PROCESS_PATH);
		}

		// Determine if a PTY will be used
		boolean isUsingPTY = (properties.get(ITerminalsConnectorConstants.PROP_PROCESS_OBJ) == null && PTY.isSupported(PTY.Mode.TERMINAL))
								|| properties.get(ITerminalsConnectorConstants.PROP_PTY_OBJ) instanceof PTY;

		boolean localEcho = false;
		if (!properties.containsKey(ITerminalsConnectorConstants.PROP_LOCAL_ECHO)
				|| !(properties.get(ITerminalsConnectorConstants.PROP_LOCAL_ECHO) instanceof Boolean)) {
			// On Windows, turn on local echo by default if no PTY is used (bug 433645)
			if (Platform.OS_WIN32.equals(Platform.getOS())) {
				localEcho = !isUsingPTY;
			}
		} else {
			localEcho = ((Boolean)properties.get(ITerminalsConnectorConstants.PROP_LOCAL_ECHO)).booleanValue();
		}

		String lineSeparator = null;
		if (!properties.containsKey(ITerminalsConnectorConstants.PROP_LINE_SEPARATOR)
				|| !(properties.get(ITerminalsConnectorConstants.PROP_LINE_SEPARATOR) instanceof String)) {
			// No line separator will be set if a PTY is used
			if (!isUsingPTY) {
				lineSeparator = Platform.OS_WIN32.equals(Platform.getOS()) ? ILineSeparatorConstants.LINE_SEPARATOR_CRLF : ILineSeparatorConstants.LINE_SEPARATOR_LF;
			}
		} else {
			lineSeparator = (String)properties.get(ITerminalsConnectorConstants.PROP_LINE_SEPARATOR);
		}

		String arguments = (String)properties.get(ITerminalsConnectorConstants.PROP_PROCESS_ARGS);
		Process process = (Process)properties.get(ITerminalsConnectorConstants.PROP_PROCESS_OBJ);
		PTY pty = (PTY)properties.get(ITerminalsConnectorConstants.PROP_PTY_OBJ);
		ITerminalServiceOutputStreamMonitorListener[] stdoutListeners = (ITerminalServiceOutputStreamMonitorListener[])properties.get(ITerminalsConnectorConstants.PROP_STDOUT_LISTENERS);
		ITerminalServiceOutputStreamMonitorListener[] stderrListeners = (ITerminalServiceOutputStreamMonitorListener[])properties.get(ITerminalsConnectorConstants.PROP_STDERR_LISTENERS);
		String workingDir = (String)properties.get(ITerminalsConnectorConstants.PROP_PROCESS_WORKING_DIR);

		String[] envp = null;
		if (properties.containsKey(ITerminalsConnectorConstants.PROP_PROCESS_ENVIRONMENT) &&
						properties.get(ITerminalsConnectorConstants.PROP_PROCESS_ENVIRONMENT) != null &&
						properties.get(ITerminalsConnectorConstants.PROP_PROCESS_ENVIRONMENT) instanceof String[]){
			envp = (String[])properties.get(ITerminalsConnectorConstants.PROP_PROCESS_ENVIRONMENT);
		}

		// Set the ECLIPSE_HOME and ECLIPSE_WORKSPACE environment variables
		List<String> envpList = new ArrayList<String>();
		if (envp != null) envpList.addAll(Arrays.asList(envp));

		// ECLIPSE_HOME
		String eclipseHomeLocation = System.getProperty("eclipse.home.location"); //$NON-NLS-1$
		if (eclipseHomeLocation != null) {
			try {
				URI uri = URIUtil.fromString(eclipseHomeLocation);
				File f = URIUtil.toFile(uri);
				envpList.add("ECLIPSE_HOME=" + f.getAbsolutePath()); //$NON-NLS-1$
			} catch (URISyntaxException e) { /* ignored on purpose */ }
		}

		// ECLIPSE_WORKSPACE
		Bundle bundle = Platform.getBundle("org.eclipse.core.resources"); //$NON-NLS-1$
		if (bundle != null && (bundle.getState() == Bundle.RESOLVED || bundle.getState() == Bundle.ACTIVE)) {
	        if (org.eclipse.core.resources.ResourcesPlugin.getWorkspace() != null
	        	            && org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot() != null
	        	            && org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot().getLocation() != null) {
	        	envpList.add("ECLIPSE_WORKSPACE=" + org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot().getLocation().toOSString()); //$NON-NLS-1$
	        }
		}

        // Convert back into a string array
        envp = envpList.toArray(new String[envpList.size()]);

		Assert.isTrue(image != null || process != null);

		// Construct the terminal settings store
		ISettingsStore store = new SettingsStore();

		// Construct the process settings
		ProcessSettings processSettings = new ProcessSettings();
		processSettings.setImage(image);
		processSettings.setArguments(arguments);
		processSettings.setProcess(process);
		processSettings.setPTY(pty);
		processSettings.setLocalEcho(localEcho);
		processSettings.setLineSeparator(lineSeparator);
		processSettings.setStdOutListeners(stdoutListeners);
		processSettings.setStdErrListeners(stderrListeners);
		processSettings.setWorkingDir(workingDir);
		processSettings.setEnvironment(envp);

		if (properties.containsKey(ITerminalsConnectorConstants.PROP_PROCESS_MERGE_ENVIRONMENT)) {
			Object value = properties.get(ITerminalsConnectorConstants.PROP_PROCESS_MERGE_ENVIRONMENT);
			processSettings.setMergeWithNativeEnvironment(value instanceof Boolean ? ((Boolean)value).booleanValue() : false);
		}

		// And save the settings to the store
		processSettings.save(store);

		// Construct the terminal connector instance
		ITerminalConnector connector = TerminalConnectorExtension.makeTerminalConnector(connectorId);
		if (connector != null) {
			// Apply default settings
			connector.makeSettingsPage();
			// And load the real settings
			connector.load(store);
		}

		return connector;
	}
}

/*******************************************************************************
 * Copyright (c) 2000, 2004 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.cdt.internal.core.index.impl;

import java.util.ArrayList;

import org.eclipse.cdt.core.index.IIndexDelta;
import org.eclipse.core.resources.IProject;

public class IndexDelta implements IIndexDelta {

	private ArrayList files = null;
	private IProject project = null;
	
	/**
	 * @param filesTrav
	 * @param project
	 * 
	 */
	public IndexDelta(IProject project, ArrayList filesTrav) {
		this.project = project;
		this.files = filesTrav;
	}

	/**
	 * @return Returns the files.
	 */
	public ArrayList getFiles() {
		return files;
	}
	/**
	 * @return Returns the project.
	 */
	public IProject getProject() {
		return project;
	}
}

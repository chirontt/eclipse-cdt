/*******************************************************************************
 * Copyright (c) 2009 Alena Laskavaia 
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Alena Laskavaia  - initial API and implementation
 *******************************************************************************/
package org.eclipse.cdt.codan.core.model;

import java.util.ArrayList;
import java.util.Collection;

/**
 * @author Alena
 * 
 */
public class ProblemsProfile implements IProblemsProfile, Cloneable {
	private IProblemCategory rootCategory = new CodanProblemCategory("root",
			"root");

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.eclipse.cdt.codan.core.model.IProblemsProfile#getProblem(java.lang
	 * .String)
	 */
	@Override
	public IProblem findProblem(String id) {
		return getRoot().findProblem(id);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.cdt.codan.core.model.IProblemsProfile#getProblems()
	 */
	@Override
	public IProblem[] getProblems() {
		Collection<IProblem> problems = new ArrayList<IProblem>();
		collectProblems(getRoot(), problems);
		return problems.toArray(new IProblem[problems.size()]);
	}

	/**
	 * @param root
	 * @param problems
	 */
	protected void collectProblems(IProblemCategory parent,
			Collection<IProblem> problems) {
		Object[] children = parent.getChildren();
		for (Object object : children) {
			if (object instanceof IProblemCategory) {
				IProblemCategory cat = (IProblemCategory) object;
				collectProblems(cat, problems);
			} else if (object instanceof IProblem) {
				problems.add((IProblem) object);
			}
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.cdt.codan.core.model.IProblemsProfile#getRoot()
	 */
	@Override
	public IProblemCategory getRoot() {
		return rootCategory;
	}

	public void addProblem(IProblem p, IProblemCategory cat) {
		if (cat == null)
			cat = getRoot();
		((CodanProblemCategory) cat).addChild(p);
	}

	public IProblemCategory findCategory(String id) {
		return getRoot().findCategory(id);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.lang.Object#clone()
	 */
	@Override
	public Object clone() {
		try {
			ProblemsProfile clone = (ProblemsProfile) super.clone();
			clone.rootCategory = (IProblemCategory) ((CodanProblemCategory) this.rootCategory)
					.clone();
			return clone;
		} catch (CloneNotSupportedException e) {
			return this;
		}
	}
}

-----------------------------------------------------------------------------------
-- Copyright (c) 2006, 2008 IBM Corporation and others.
-- All rights reserved. This program and the accompanying materials
-- are made available under the terms of the Eclipse Public License v1.0
-- which accompanies this distribution, and is available at
-- http://www.eclipse.org/legal/epl-v10.html
--
-- Contributors:
--     IBM Corporation - initial API and implementation
-----------------------------------------------------------------------------------


$Notice
-- Copied into all files generated by LPG
/./*******************************************************************************
 * Copyright (c) 2006, 2008 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *********************************************************************************/
 
 // This file was generated by LPG
./
$End


$Define
	-- These macros allow the template and header code to be customized by an extending parser.
	$ast_class /.Object./
	$data_class /. Object ./ -- allow anything to be passed between actions
	
	--$additional_interfaces /. , IParserActionTokenProvider, IParser ./
	$additional_interfaces /. ./
	
	$build_action_class /.  ./
	$resolve_action_class /.  ./
	$node_factory_create_expression /.  ./
	
	
	$lexer_class /.  ./
	$action_class /.  ./
	
	
	$UndoResolver /.$Undo action.resolver.undo(); $EndUndo./
	
	$Resolve /. $BeginTrial $resolve.
	./
	$EndResolve /. $EndTrial 
		$UndoResolver
	./ -- undo actions are automatically generated for binding resolution actions
	
	$Builder /. $BeginFinal  $builder.
	./ 
	$EndBuilder /. /*$builder.getASTStack().print();*/ $EndFinal ./

	$Build /. $Action $Builder ./
	$EndBuild /. $EndBuilder $EndAction ./
	
	$resolve /. action.resolver./
	$builder /. action.builder./


-- comment out when using trial/undo
	--$Action /. $BeginAction ./
	--$BeginFinal /. ./
	--$EndFinal /. ./
	--$BeginTrial /. ./
	--$EndTrial /. ./
	--$Undo /. ./
	--$EndUndo /. ./
$End


$Headers
/.
	private $action_class action;	
	
	//public $action_type() {  // constructor
	//}
	
	private void initActions(IASTTranslationUnit tu) {
	    // binding resolution actions need access to IASTName nodes, temporary
	    action = new $action_class();
		action.resolver = new $resolve_action_class(this);
		action.builder  = new $build_action_class($node_factory_create_expression, this, tu);
		action.builder.setTokenMap($sym_class.orderedTerminalSymbols);
		//setParserAction(action);
	}
	
	
	public void addToken(IToken token) {
		token.setKind(mapKind(token.getKind()));
		super.addToken(token);
	}
	
	public void setTokens(List<IToken> tokens) {
		resetTokenStream();
		for(IToken token : tokens) {
			addToken(token);
		}
	}
	
	public IASTCompletionNode parse(IASTTranslationUnit tu) {
		// this has to be done, or... kaboom!
		setStreamLength(getSize());
		initActions(tu);
		
		final int errorRepairCount = -1;  // -1 means full error handling
		parser(null, errorRepairCount); // do the actual parse
		super.resetTokenStream(); // allow tokens to be garbage collected
	
		// the completion node may be null
		IASTCompletionNode compNode = action.builder.getASTCompletionNode();
	
		action = null;
		parserAction = null;
		return compNode;
	}


	public int getKind(int i) {
		int kind = super.getKind(i);
		// lexer feedback hack!
		//if(kind == $sym_class.TK_identifier && action.resolver.isTypedef(getTokenText(i))) {
		//	kind = $sym_class.TK_TypedefName;
		//}
		return kind;
	}
	
./
$End
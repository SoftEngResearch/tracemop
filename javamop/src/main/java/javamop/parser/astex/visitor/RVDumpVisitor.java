// Copyright (c) 2002-2014 JavaMOP Team. All Rights Reserved.
package javamop.parser.astex.visitor;

import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.printer.configuration.PrinterConfiguration;
import javamop.parser.ast.aspectj.BaseTypePattern;
import javamop.parser.ast.aspectj.TypePattern;
import javamop.parser.ast.mopspec.EventDefinition;
import javamop.parser.ast.mopspec.MOPParameter;
import javamop.parser.ast.mopspec.MOPParameters;

/**
 * @author Qingzhou Luo
 */

public class RVDumpVisitor extends DumpVisitor {

	public RVDumpVisitor(PrinterConfiguration configuration) {
		super(configuration);
	}

	@Override
	public void visit(EventDefinition e, Void arg) {
		if (e.isCreationEvent()) {
			printer.print("creation ");
		} else if (e.isBlockingEvent()) {
			printer.print("blocking ");
		} else if (e.isStaticEvent()) {
			printer.print("static ");
		}
		printer.print("event " + e.getId());
		// linjus: I fixed this.
		// The following is wrong. First of all, it is unnatural that a dump visitor has a side-effect.
		// Second, modifying 'parameters' is fine when .rvm files are generated, because this is the first
		// step. However, during the second pass, for creating .aj files, these added parameters will result
		// in duplicated parameters.
//		MOPParameters parameters = e.getParameters();
		MOPParameters parameters = new MOPParameters(e.getParameters());
		
		if (e.hasReturning()) {
			parameters.addAll(e.getRetVal().toList());
		}
		if (e.hasThrowing()) {
			parameters.addAll(e.getThrowVal().toList());
		}
		if (e.has__STATICSIG()) {
			TypePattern type = new BaseTypePattern(null, "org.aspectj.lang.Signature");
			MOPParameter param = new MOPParameter(null, type, "staticsig");
			parameters.add(param);
		}
		printSpecParameters(parameters, arg);
		if (e.getCondition() != null && e.getCondition().length() > 0) {
			printer.print("{\n");
			printer.print("if ( ! (" + e.getCondition() + ") ) {\n");
			printer.print("return false;\n");
			printer.print("}\n");
		}
		
		if (e.getAction() != null) {
			e.getAction().accept(this, arg);
		}
		printer.println();
		if (e.getCondition() != null && e.getCondition().length() > 0) {
			printer.print("}\n");
		}
	}
	
	public void visit(NameExpr n, Void arg) {
		String name = n.getName().asString();
		if (name.equals("__STATICSIG"))
			name = "staticsig";
		printer.print(name);
	}
}

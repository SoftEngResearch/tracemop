package com.runtimeverification.rvmonitor.java.rvj.output.combinedoutputcode.newindexingtree;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.runtimeverification.rvmonitor.java.rvj.output.codedom.CodeAssignStmt;
import com.runtimeverification.rvmonitor.java.rvj.output.codedom.CodeBinOpExpr;
import com.runtimeverification.rvmonitor.java.rvj.output.codedom.CodeBlockStmt;
import com.runtimeverification.rvmonitor.java.rvj.output.codedom.CodeClassDef;
import com.runtimeverification.rvmonitor.java.rvj.output.codedom.CodeCastExpr;
import com.runtimeverification.rvmonitor.java.rvj.output.codedom.CodeConditionStmt;
import com.runtimeverification.rvmonitor.java.rvj.output.codedom.CodeExpr;
import com.runtimeverification.rvmonitor.java.rvj.output.codedom.CodeExprStmt;
import com.runtimeverification.rvmonitor.java.rvj.output.codedom.CodeFieldRefExpr;
import com.runtimeverification.rvmonitor.java.rvj.output.codedom.CodeLiteralExpr;
import com.runtimeverification.rvmonitor.java.rvj.output.codedom.CodeMemberField;
import com.runtimeverification.rvmonitor.java.rvj.output.codedom.CodeMemberMethod;
import com.runtimeverification.rvmonitor.java.rvj.output.codedom.CodeMethodInvokeExpr;
import com.runtimeverification.rvmonitor.java.rvj.output.codedom.CodeNewExpr;
import com.runtimeverification.rvmonitor.java.rvj.output.codedom.CodeReturnStmt;
import com.runtimeverification.rvmonitor.java.rvj.output.codedom.CodeStmt;
import com.runtimeverification.rvmonitor.java.rvj.output.codedom.CodeStmtCollection;
import com.runtimeverification.rvmonitor.java.rvj.output.codedom.CodeVarDeclStmt;
import com.runtimeverification.rvmonitor.java.rvj.output.codedom.CodeVarRefExpr;
import com.runtimeverification.rvmonitor.java.rvj.output.codedom.helper.CodeHelper;
import com.runtimeverification.rvmonitor.java.rvj.output.codedom.helper.CodePair;
import com.runtimeverification.rvmonitor.java.rvj.output.codedom.helper.CodeVariable;
import com.runtimeverification.rvmonitor.java.rvj.output.codedom.helper.ICodeFormatter;
import com.runtimeverification.rvmonitor.java.rvj.output.codedom.type.CodeRVType;
import com.runtimeverification.rvmonitor.java.rvj.output.codedom.type.CodeType;
import com.runtimeverification.rvmonitor.java.rvj.output.combinedoutputcode.event.itf.WeakReferenceVariables;
import com.runtimeverification.rvmonitor.java.rvj.output.monitor.SuffixMonitor;
import com.runtimeverification.rvmonitor.java.rvj.output.monitorset.MonitorSet;
import com.runtimeverification.rvmonitor.java.rvj.parser.ast.rvmspec.RVMParameter;
import com.runtimeverification.rvmonitor.java.rvj.parser.ast.rvmspec.RVMParameters;

class NativeIndexingTreeImplementation extends IndexingTreeImplementation {
    private final CodeMemberField nativeField;
    private final RVMParameters backingParams;
    private final Map<RVMParameters, Entry> logicalEntries;
    private final Map<RVMParameters, CodeMemberField> factoryFields;
    private final boolean timeTracking;
    /*
     * Optional factory for the simple full-key leaf case. The event body only
     * uses it when a tree miss can allocate the monitor before D(X)'s
     * miss-only bookkeeping runs.
     */
    private CodeMemberField leafFactoryField;
    private CodeMemberField leafCreatedField;

    NativeIndexingTreeImplementation(IndexingTreeInterface master,
            String outputName, RVMParameters specParams, RVMParameters backingParams,
            RVMParameters contentParams, MonitorSet set, SuffixMonitor monitor,
            EventKind evttype, boolean timetrack,
            Map<RVMParameters, Entry> logicalEntries) {
        super(master, outputName, specParams, backingParams, contentParams, set,
                monitor, evttype, timetrack);

        this.backingParams = backingParams;
        this.timeTracking = timetrack;
        this.logicalEntries = new HashMap<RVMParameters, Entry>(logicalEntries);
        this.factoryFields = new TreeMap<RVMParameters, CodeMemberField>();

        CodeType type = CodeHelper.RuntimeType.getNativeIndexingTree();
        String name = IndexingTreeNameMangler.fieldName(outputName, backingParams,
                contentParams);
        this.nativeField = new CodeMemberField(name, false, true, true, type,
                new CodeNewExpr(type));

        for (Map.Entry<RVMParameters, Entry> logicalEntry : logicalEntries
                .entrySet()) {
            Entry entry = logicalEntry.getValue();
            if (this.shouldCreateEntry(entry)) {
                CodeMemberField factory = this.generateFactoryField(
                        logicalEntry.getKey(), entry);
                this.factoryFields.put(logicalEntry.getKey(), factory);
            }
        }

        this.buildLeafGetOrCreateFactory();
    }

    private void buildLeafGetOrCreateFactory() {
        if (this.timeTracking)
            return;
        Entry leaf = this.logicalEntries.get(this.backingParams);
        if (leaf == null || leaf.getSet() != null)
            return;

        this.leafCreatedField = new CodeMemberField(
                this.nativeField.getName() + "_leafCreated", false, true, false,
                CodeType.bool(), CodeLiteralExpr.bool(false));

        CodeType factoryType = CodeHelper.RuntimeType.getRuntimeMonitorFactory();
        CodeClassDef factoryClass = CodeClassDef.anonymous(factoryType);
        CodeStmtCollection body = new CodeStmtCollection();
        body.add(new CodeAssignStmt(new CodeFieldRefExpr(this.leafCreatedField),
                CodeLiteralExpr.bool(true)));
        body.add(new CodeReturnStmt(new CodeNewExpr(leaf.getCodeType())));
        CodeMemberMethod createMonitor = new CodeMemberMethod("createMonitor",
                true, false, false, CodeType.object(), true, body);
        factoryClass.addMethod(createMonitor);

        this.leafFactoryField = new CodeMemberField(
                this.nativeField.getName() + "_leafFactory", false, true, true,
                factoryType, new CodeNewExpr(factoryType, factoryClass));
    }

    @Override
    public String getName() {
        return this.nativeField.getName();
    }

    @Override
    public CodeMemberField getField() {
        return this.nativeField;
    }

    @Override
    public CodeType getCodeType() {
        return this.nativeField.getType();
    }

    @Override
    boolean isNative() {
        return true;
    }

    @Override
    Entry lookupEntry(RVMParameters params) {
        Entry entry = this.logicalEntries.get(params);
        if (entry == null)
            throw new IllegalArgumentException();
        return entry;
    }

    @Override
    public void getCode(ICodeFormatter fmt) {
        this.nativeField.getCode(fmt);
        for (CodeMemberField factory : this.factoryFields.values())
            factory.getCode(fmt);
        if (this.leafFactoryField != null) {
            this.leafCreatedField.getCode(fmt);
            this.leafFactoryField.getCode(fmt);
        }
    }

    @Override
    public boolean hasLeafGetOrCreate(RVMParameters queryprms) {
        return this.leafFactoryField != null
                && this.backingParams.equals(queryprms);
    }

    @Override
    public CodeStmt generateLeafCreatedResetStmt() {
        return new CodeAssignStmt(new CodeFieldRefExpr(this.leafCreatedField),
                CodeLiteralExpr.bool(false));
    }

    @Override
    public CodeExpr generateLeafCreatedRef() {
        return new CodeFieldRefExpr(this.leafCreatedField);
    }

    @Override
    public CodeExpr generateLeafGetOrCreateExpr(RVMParameters queryprms) {
        Entry entry = this.lookupEntry(queryprms);
        List<CodeExpr> args = new ArrayList<CodeExpr>();
        args.add(new CodeFieldRefExpr(this.leafFactoryField));
        args.addAll(this.getPaddedArgs(queryprms));
        CodeMethodInvokeExpr invoke = new CodeMethodInvokeExpr(CodeType.object(),
                this.getRootRef(), this.getMethodName("getOrCreate"), args);
        return new CodeCastExpr(entry.getCodeType(), invoke);
    }

    private CodeFieldRefExpr getRootRef() {
        return new CodeFieldRefExpr(this.nativeField);
    }

    private List<CodeExpr> getPaddedArgs(RVMParameters queryprms) {
        List<CodeExpr> args = new ArrayList<CodeExpr>();
        for (RVMParameter param : this.backingParams) {
            if (queryprms.contains(param)) {
                CodeVariable var = new CodeVariable(CodeType.object(),
                        param.getName());
                args.add(new CodeVarRefExpr(var));
            } else {
                args.add(CodeLiteralExpr.nul());
            }
        }
        return args;
    }

    private String getMethodName(String prefix) {
        int arity = this.backingParams.size();
        if (arity >= 1 && arity <= 4)
            return prefix + arity;
        return prefix;
    }

    private CodeExpr generateGetExpr(RVMParameters queryprms, Entry entry) {
        CodeMethodInvokeExpr invoke = new CodeMethodInvokeExpr(CodeType.object(),
                this.getRootRef(), this.getMethodName("get"),
                this.getPaddedArgs(queryprms));
        return new CodeCastExpr(entry.getCodeType(), invoke);
    }

    private CodeExpr generateGetOrCreateExpr(RVMParameters queryprms,
            Entry entry) {
        List<CodeExpr> args = new ArrayList<CodeExpr>();
        args.add(new CodeFieldRefExpr(this.lookupFactoryField(queryprms)));
        args.addAll(this.getPaddedArgs(queryprms));

        CodeMethodInvokeExpr invoke = new CodeMethodInvokeExpr(CodeType.object(),
                this.getRootRef(), this.getMethodName("getOrCreate"), args);
        return new CodeCastExpr(entry.getCodeType(), invoke);
    }

    private CodeStmtCollection generatePutEntryCode(RVMParameters queryprms,
            CodeExpr valueref) {
        List<CodeExpr> args = new ArrayList<CodeExpr>();
        args.add(valueref);
        args.addAll(this.getPaddedArgs(queryprms));
        CodeMethodInvokeExpr invoke = new CodeMethodInvokeExpr(CodeType.foid(),
                this.getRootRef(), "put", args);
        return new CodeStmtCollection(new CodeExprStmt(invoke));
    }

    @Override
    CodeStmtCollection generateNativePutCode(RVMParameters queryprms,
            CodeExpr valueref) {
        return this.generatePutEntryCode(queryprms, valueref);
    }

    private boolean shouldCreateEntry(Entry entry) {
        return entry.getSet() != null;
    }

    private CodeMemberField lookupFactoryField(RVMParameters queryprms) {
        CodeMemberField field = this.factoryFields.get(queryprms);
        if (field == null)
            throw new IllegalArgumentException();
        return field;
    }

    private CodeMemberField generateFactoryField(RVMParameters queryprms,
            Entry entry) {
        CodeType factoryType = CodeHelper.RuntimeType
                .getRuntimeMonitorFactory();
        CodeClassDef factoryClass = CodeClassDef.anonymous(factoryType);

        CodeStmtCollection body = new CodeStmtCollection(new CodeReturnStmt(
                this.generateEntryCreationExpr(entry)));
        CodeMemberMethod createMonitor = new CodeMemberMethod("createMonitor",
                true, false, false, CodeType.object(), true, body);
        factoryClass.addMethod(createMonitor);

        String name = this.nativeField.getName() + "_"
                + queryprms.parameterStringUnderscore()
                + "_RuntimeMonitorFactory";
        CodeExpr init = new CodeNewExpr(factoryType, factoryClass);
        return new CodeMemberField(name, false, true, true, factoryType, init);
    }

    private CodeExpr generateEntryCreationExpr(Entry entry) {
        CodeType type = entry.getCodeType();
        if (type instanceof CodeRVType.Tuple) {
            CodeRVType.Tuple tuple = (CodeRVType.Tuple) type;
            List<CodeExpr> args = new ArrayList<CodeExpr>();
            for (CodeRVType elem : tuple.getElements()) {
                if (elem instanceof CodeRVType.MonitorSet)
                    args.add(new CodeNewExpr(elem));
                else
                    args.add(CodeLiteralExpr.nul());
            }
            return new CodeNewExpr(type, args);
        }

        if (entry.getSet() != null)
            return new CodeNewExpr(entry.getSet());

        throw new IllegalArgumentException();
    }

    private CodeStmtCollection emitEntryAndOptionalField(Entry entry,
            CodeVarRefExpr entryref, Access access,
            StmtCollectionInserter<CodeExpr> inserter) {
        CodeStmtCollection stmts = new CodeStmtCollection();

        CodeStmtCollection lastEntry = inserter.insertLastEntry(entry, entryref);
        if (lastEntry != null)
            stmts.add(lastEntry);

        if (access != Access.Entry) {
            CodeStmtCollection fieldStmts = Entry.generateFieldGetCodeInVisitList(
                    access, inserter, entry, entryref);
            if (fieldStmts != null)
                stmts.add(fieldStmts);
        }

        return stmts;
    }

    private CodeStmtCollection generateFindCode(RVMParameters queryprms,
            final Access access, final StmtCollectionInserter<CodeExpr> inserter,
            boolean create, boolean suppressLastNullCheck) {
        CodeStmtCollection stmts = new CodeStmtCollection();
        final Entry entry = this.lookupEntry(queryprms);

        CodeVariable node = CodeHelper.VariableName.getInternalNode(
                entry.getCodeType(), queryprms, queryprms.size() - 1);
        final CodeVarRefExpr noderef = new CodeVarRefExpr(node);
        CodeExpr lookup = create && this.shouldCreateEntry(entry)
                ? this.generateGetOrCreateExpr(queryprms, entry)
                : this.generateGetExpr(queryprms, entry);
        stmts.add(new CodeVarDeclStmt(node, lookup));

        CodeStmtCollection body = this.emitEntryAndOptionalField(entry, noderef,
                access, inserter);
        if (!create && !suppressLastNullCheck) {
            stmts.add(new CodeConditionStmt(CodeBinOpExpr.isNotNull(noderef),
                    body));
        } else {
            stmts.add(body);
        }

        return stmts;
    }

    private CodeStmtCollection promoteSeparateBlock(CodeStmtCollection body,
            String comment) {
        CodeStmtCollection stmts = new CodeStmtCollection();
        stmts.comment(comment);
        stmts.add(body);
        return new CodeStmtCollection(new CodeBlockStmt(stmts));
    }

    @Override
    CodeStmtCollection generateFindOrCreateEntryCode(RVMParameters queryprms,
            RVMParameters specprms, WeakReferenceVariables weakrefs,
            StmtCollectionInserter<CodeExpr> inserter) {
        CodeStmtCollection stmts = this.generateFindCode(queryprms, Access.Entry,
                inserter, true, false);
        return this.promoteSeparateBlock(stmts, "FindOrCreateEntry");
    }

    @Override
    CodeStmtCollection generateFindEntryWithStrongRefCode(
            RVMParameters queryprms, RVMParameters specprms,
            WeakReferenceVariables weakrefs, StmtCollectionInserter<CodeExpr> inserter,
            boolean suppressLastNullCheck) {
        CodeStmtCollection stmts = this.generateFindCode(queryprms, Access.Entry,
                inserter, false, suppressLastNullCheck);
        return this.promoteSeparateBlock(stmts, "FindEntry");
    }

    @Override
    CodeStmtCollection generateFindOrCreateCode(RVMParameters queryprms,
            Access access, RVMParameters specprms, WeakReferenceVariables weakrefs,
            StmtCollectionInserter<CodeExpr> inserter) {
        CodeStmtCollection stmts = this.generateFindCode(queryprms, access,
                inserter, true, false);
        return this.promoteSeparateBlock(stmts, "FindOrCreate");
    }

    @Override
    CodeStmtCollection generateFindCode(RVMParameters queryprms, Access access,
            RVMParameters specprms, WeakReferenceVariables weakrefs,
            StmtCollectionInserter<CodeExpr> inserter) {
        CodeStmtCollection stmts = this.generateFindCode(queryprms, access,
                inserter, false, false);
        return this.promoteSeparateBlock(stmts, "FindCode");
    }

    @Override
    CodeStmtCollection generateFindCode(CodeExpr root, RVMParameters queryprms,
            Access access, RVMParameters specprms, WeakReferenceVariables weakrefs,
            StmtCollectionInserter<CodeExpr> inserter) {
        return this.generateFindCode(queryprms, access, specprms, weakrefs,
                inserter);
    }

    @Override
    CodeStmtCollection generateInsertMonitorCode(RVMParameters queryprms,
            RVMParameters specprms, WeakReferenceVariables weakrefs,
            final CodeVarRefExpr monitorref) {
        StmtCollectionInserter<CodeExpr> inserter = new StmtCollectionInserter<CodeExpr>() {
            @Override
            public CodeStmtCollection insertLastEntry(Entry entry,
                    CodeExpr entryref) {
                CodeStmtCollection stmts = new CodeStmtCollection();
                CodePair<CodeVarRefExpr> codepair = entry.generateFieldGetCode(
                        entryref, Access.Set, "target");
                stmts.add(codepair.getGeneratedCode());

                CodeVarRefExpr fieldref = codepair.getLogicalReturn();
                CodeMethodInvokeExpr invoke = new CodeMethodInvokeExpr(
                        CodeType.foid(), fieldref, "add", monitorref);
                stmts.add(new CodeExprStmt(invoke));
                return stmts;
            }
        };

        CodeStmtCollection stmts = this.generateFindCode(queryprms, Access.Entry,
                inserter, true, false);
        return this.promoteSeparateBlock(stmts, "InsertMonitor");
    }
}

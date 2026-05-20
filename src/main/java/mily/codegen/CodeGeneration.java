package mily.codegen;

import mily.codegen.blocks.*;
import mily.codegen.lines.*;
import mily.parsing.*;
import mily.parsing.callables.*;
import mily.parsing.invokes.*;
import mily.structures.dataobjects.*;
import mily.tokens.*;
import mily.utils.*;

import java.util.*;

import static mily.codegen.Mlogs.*;
import static mily.constants.Functions.*;
import static mily.constants.Keywords.*;
import static mily.processing.Validation.*;

public class CodeGeneration {

    public static final String POINTER_VARIABLE = "mem_pointer";
    private static Map<Class<? extends EvaluatorNode>, ScopeFunctionConsumer<EvaluatorNode, ScopeNode, IRScopeConfig, IRFunction, Integer>> map = new HashMap<>();
    static {
        map.put(CallerNode.class, (member, scopeNode,irScopeConfig, function, depth) -> {
            if (member instanceof CallerNode fnCall) {
                CallableSignature sig = fnCall.signature();
                CallableNode callable = irScopeConfig.callableNodeMap().get(sig);

                if (callable instanceof FunctionDeclareNode) {
                    generateFunctionCall(irScopeConfig, fnCall, depth);

                } else if (callable instanceof RawTemplateDeclareNode) {
                    generateRawTemplateInvoke(irScopeConfig, fnCall, null, depth);
                }
            }
        });

        map.put(OperationNode.class, (member, scopeNode,irScopeConfig, function, depth) -> {
            if (member instanceof OperationNode operationNode) {
                if (operationNode.isReturnOperation()) {
                    if (function == null)
                        throw new Exception("Return operation found outside a function at line " + operationNode.nameToken.line);

                    if (!getOperationType(operationNode, false).equals(DATATYPE_VOID)) {
                        addOperationIRBlock(irScopeConfig, operationNode, function.getReturnVar(), depth);
                    }
                    irScopeConfig.irCode().addSingleLineBlock(new SetLine("@counter", function.getCallbackVar(), depth));
                }
            }
        });

        map.put(FunctionDeclareNode.class, (member, scopeNode, irScopeConfig, function, depth) -> {
            if (member instanceof FunctionDeclareNode fn) {
                generateFunctionDeclare(fn, irScopeConfig, depth);
            }
        });

        map.put(DeclarationNode.class, (member, scopeNode,irScopeConfig, function, depth) -> {
            if (member instanceof DeclarationNode declarationNode) {
                irScopeConfig.declarationMap().put(declarationNode.getName(), declarationNode);

                if (declarationNode.memberCount() > 0 && declarationNode.getMember(0) instanceof OperationNode op) {
                    IROperation declaredOp = addOperationIRBlock(irScopeConfig, op, declarationNode.getName(), depth);

                    if (declarationNode.getType().typeString.equals(DATATYPE_PTR.typeString)) {
                        Line lastLine = declaredOp.lineList.get(declaredOp.lineList.size() - 1);

                        if (lastLine instanceof VariableLine variableLine) {

                            // if the assigned value is a reference then just assign it normally
                            if (!getOperationType(op, false).typeString.equals(DATATYPE_PTR.typeString)) {
                                // if it is a set, just replace it with a write line
                                String oldVarName = variableLine.getVarName();
                                String ptrValueName = "value_" + oldVarName;

                                if (variableLine instanceof SetLine setLine) {
                                    declaredOp.lineList.remove(declaredOp.lineList.size() - 1);
                                    declaredOp.lineList.add(new WriteLine(setLine.getValue(), "cell1", POINTER_VARIABLE, depth));
                                } else {
                                    // if its an op, overwrite the var name of the evaluated value
                                    variableLine.setVarName(ptrValueName);
                                    declaredOp.lineList.add(new WriteLine(variableLine.getVarName(), "cell1", POINTER_VARIABLE, depth));
                                }
                                declaredOp.lineList.add(new SetLine(oldVarName, POINTER_VARIABLE, depth));
                                declaredOp.lineList.add(new BinaryOp(POINTER_VARIABLE, KEY_OP_ADD, POINTER_VARIABLE, "1", depth));
                            }

                        } else {
                            throw new Exception("Why is the ptr var declaration line not a VariableLine?");
                        }
                    }

                } else if (declarationNode.memberCount() == 0) {

                    // NOTE: null declaration does nothing
                } else {
                    throw new Exception("Malformed declaration node found on codegen stage");
                }
            }
        });

        map.put(AssignmentNode.class, (member, scopeNode, irScopeConfig, function, depth) -> {
            if (member instanceof AssignmentNode as) {
                // first member of assignment nodes should always be an operator
                // otherwise throw an error
                if (member.memberCount() <= 0 || !(member.getMember(0) instanceof OperationNode op))
                    throw new Exception("Malformed assignment node found on codegen stage");

                IROperation irOperation = addOperationIRBlock(irScopeConfig, op, as.getName(), depth);

                // get type of declarator to see if it is a reference
                DeclarationNode declarator = irScopeConfig.declarationMap().get(as.getName());

                if (declarator.getType().typeString.equals(DATATYPE_PTR.typeString)) {
                    // replace the last operation with a memcell write
                    Line lastline = irOperation.lineList.get(irOperation.lineList.size() - 1);

                    if (!getOperationType(op, false).typeString.equals(DATATYPE_PTR.typeString)) {
                        if (lastline instanceof SetLine setLine) {
                            irOperation.lineList.remove(irOperation.lineList.size() - 1);
                            irOperation.addLine(new WriteLine(setLine.getValue(), "cell1", declarator.getName(), setLine.getIndent()));

                        } else if (lastline instanceof VariableLine variableLine) {
                            // if its an op
                            variableLine.setVarName("value_" + variableLine.getVarName());
                            irOperation.addLine(new WriteLine(variableLine.getVarName(), "cell1", declarator.getName(), depth));
                        }
                    }
                }
            }
        });

        map.put(IfStatementNode.class, (member, scopeNode,irScopeConfig, function, depth) -> {
            if (member instanceof IfStatementNode ifs) {
                generateBranchStatement(ifs, irScopeConfig, function, depth);
            }
        });

        map.put(WhileLoopNode.class, (member, scopeNode, irScopeConfig, function, depth) -> {
            if (member instanceof WhileLoopNode whileLoop) {
                // todo unify copy pastes
                String whileHashCode = "" + irScopeConfig.hashCodeSimplifier().simplifyHash(whileLoop.hashCode());
                String startLabelString = "while_loop_start_" + whileHashCode;
                String endLabelString = "while_loop_end_" + whileHashCode;
                String checkpointVariableString = "while_loop_checkpoint" + whileHashCode;

                // set checkpoint
                irScopeConfig.irCode().addSingleLineBlock(new SetLine(checkpointVariableString, POINTER_VARIABLE, depth));
                // loop start label
                irScopeConfig.irCode().addSingleLineBlock(new Label(startLabelString, depth));
                // set pointer to checkpoint (prevent memory leaks on loops
                irScopeConfig.irCode().addSingleLineBlock(new SetLine(POINTER_VARIABLE, checkpointVariableString, depth));

                // create exit jump
                boolean invertCondition = true;
                Jump jump = createConditionalJump(
                        irScopeConfig,
                        whileLoop.getExpression(),
                        whileHashCode,
                        endLabelString,
                        invertCondition,
                        depth
                );
                irScopeConfig.irCode().addSingleLineBlock(jump);

                // code block
                generateScopeRecursive(whileLoop.getScope(), irScopeConfig.copy(), function, depth + 1);

                // always jump
                irScopeConfig.irCode().addSingleLineBlock(new Jump("always", startLabelString, depth));

                // loop end label
                irScopeConfig.irCode().addSingleLineBlock(new Label(endLabelString, depth));

            }
        });

        map.put(ForLoopNode.class, (member, scopeNode, irScopeConfig, function, depth) -> {
            if (member instanceof ForLoopNode forLoop) {
                // todo unify copy pastes
                String forLoopHashCode = "" + irScopeConfig.hashCodeSimplifier().simplifyHash(forLoop.hashCode());
                String startLabelString = "for_loop_start_" + forLoopHashCode;
                String endLabelString = "for_loop_end_" + forLoopHashCode;
                String checkpointVariableString = "for_loop_checkpoint" + forLoopHashCode;

                // initial
                VariableNode initial = forLoop.getInitial();
                if (initial == null || initial.memberCount() <= 0 || !(initial.getMember(0) instanceof OperationNode))
                    throw new Exception("Malformed for loop updater found on codegen stage");

                addOperationIRBlock(irScopeConfig, (OperationNode) initial.getMember(0), initial.getName(), depth);

                // set checkpoint
                irScopeConfig.irCode().addSingleLineBlock(new SetLine(checkpointVariableString, POINTER_VARIABLE, depth));
                // loop start label
                irScopeConfig.irCode().addSingleLineBlock(new Label(startLabelString, depth));
                // set pointer to checkpoint (prevent memory leaks on loops
                irScopeConfig.irCode().addSingleLineBlock(new SetLine(POINTER_VARIABLE, checkpointVariableString, depth));

                // create exit jump
                boolean invertCondition = true;
                Jump jump = createConditionalJump(
                        irScopeConfig,
                        forLoop.getCondition(),
                        forLoopHashCode,
                        endLabelString,
                        invertCondition,
                        depth
                );
                irScopeConfig.irCode().addSingleLineBlock(jump);

                // code block
                generateScopeRecursive(forLoop.getScope(), irScopeConfig.copy(), function, depth + 1);

                // updater
                AssignmentNode updater = forLoop.getUpdater();
                if (updater == null || updater.memberCount() <= 0 || !(updater.getMember(0) instanceof OperationNode))
                    throw new Exception("Malformed for loop updater found on codegen stage");

                addOperationIRBlock(irScopeConfig, (OperationNode) updater.getMember(0), updater.getName(), depth);

                // always jump
                irScopeConfig.irCode().addSingleLineBlock(new Jump("always", startLabelString, depth));

                // loop end label
                irScopeConfig.irCode().addSingleLineBlock(new Label(endLabelString, depth));
            }
        });

        map.put(RawTemplateDeclareNode.class, (member, scopeNode,irScopeConfig, function, depth) -> {
            if (member instanceof RawTemplateDeclareNode rawTemplateDeclareNode) {
                irScopeConfig.callableNodeMap().put(rawTemplateDeclareNode.signature(), rawTemplateDeclareNode);
            }
        });
    }

    public static IRCode generateIRCode(EvaluatorTree evaluatorTree, boolean generateComments, boolean debugMode) throws Exception {
        IRScopeConfig irScopeConfig = new IRScopeConfig(
                new IRCode(),
                new HashMap<>(),
                new HashMap<>(),
                new HashMap<>(),
                new HashCodeSimplifier(),
                generateComments,
                debugMode
        );

        irScopeConfig.irCode().addSingleLineBlock(new SetLine(POINTER_VARIABLE, "0", 0));

        generateScopeRecursive(evaluatorTree.mainBlock, irScopeConfig.copy(), null, 0);

        irScopeConfig.irCode().addSingleLineBlock(new Stop(0));
        return irScopeConfig.irCode();
    }

    // the IRFunction here should be a part of scopeNode
    private static void generateScopeRecursive(ScopeNode scopeNode, IRScopeConfig config, IRFunction function, int depth) throws Exception {
        for (int i = 0; i < scopeNode.memberCount(); i++) {
            EvaluatorNode member = scopeNode.getMember(i);

            if (map.containsKey(member.getClass())) {
                map.get(member.getClass()).apply(member, scopeNode, config, function, depth);
            }
        }
    }

    private static void generateRawTemplateInvoke(IRScopeConfig irScopeConfig, CallerNode callerNode, String outputVariable, int depth) throws Exception {
        IRBlock irBlock = new IRBlock();
        RawTemplateDeclareNode rawTemplateDeclareNode = (RawTemplateDeclareNode) irScopeConfig.callableNodeMap().get(callerNode.signature());

        if (rawTemplateDeclareNode == null) {
            throw new Exception(String.format("Raw template with name \"%s\" does not exist", callerNode.getName()));
        }
        List<OperationNode> argOps = callerNode.getArgs();

        List<String> argNames = new ArrayList<>();
        for (OperationNode argOp : argOps) {
            IROperation opBlock = generateIROperation(irScopeConfig, argOp, depth);
            irScopeConfig.irCode().irBlocks.add(opBlock);

            Line lastLine = opBlock.lineList.get(opBlock.lineList.size() - 1);

            if (lastLine instanceof BinaryOp binaryOp) {
                argNames.add(binaryOp.getVarName());

            } else if (lastLine instanceof SetLine setLine) {
                // remove because we don't need this
                opBlock.lineList.remove(opBlock.lineList.size() - 1);
                argNames.add(setLine.getValue());

            } else {
                throw new Exception("Expected a BinaryOp or SetLine in RawTemplateInvoke on codegen stage, got \"" + lastLine.getClass() + "\" instead");
            }
        }
        String formatted;
        if (outputVariable == null) {
            formatted = rawTemplateDeclareNode.scopeAsFormatted(argNames);

        } else {
            formatted = rawTemplateDeclareNode.scopeAsFormatted(argNames, outputVariable);
        }

        String[] lineContent = formatted.split(KEY_NEWLINE);
        for (String s : lineContent) {
            if (!s.isEmpty())
                irBlock.addLine(new Line(s.trim(), depth));
        }
        if (irScopeConfig.generateComments())
            irScopeConfig.irCode().addSingleLineBlock(new CommentLine(callerNode.getName() + ":", depth));
        irScopeConfig.irCode().irBlocks.add(irBlock);
    }

    private static IRFunction generateFunctionCall(IRScopeConfig irScopeConfig, CallerNode fnCall, int depth) throws Exception {
        CallableSignature fnKey = fnCall.signature();
        FunctionDeclareNode fn = (FunctionDeclareNode) irScopeConfig.callableNodeMap().get(fnKey);
        if (!irScopeConfig.irFunctionMap().containsKey(fn))
            throw new Exception(String.format("IRFunction of key \"%s\" does not exist", fnKey));

        IRFunction calledFunction = irScopeConfig.irFunctionMap().get(fn);

        for (int a = 0; a < fnCall.getArgCount(); a++) {
            addOperationIRBlock(irScopeConfig, fnCall.getArg(a), calledFunction.getArg(a), depth);
        }

        if (irScopeConfig.generateComments())
            irScopeConfig.irCode().addSingleLineBlock(new CommentLine("call: " + fnKey, depth));
        irScopeConfig.irCode().addSingleLineBlock(new BinaryOp(calledFunction.getCallbackVar(), KEY_OP_ADD, "@counter", "1", depth));
        irScopeConfig.irCode().addSingleLineBlock(new Jump("always", calledFunction.getCallLabel(), depth));

        return calledFunction;
    }

    private static void generateFunctionDeclare(FunctionDeclareNode fn, IRScopeConfig irScopeConfig, int depth) throws Exception {
        CallableSignature fnKey = fn.signature();
        String startJumpLabel = fnKey + "_start";
        String endJumpLabel = fnKey + "_end";
        String callbackVar = fnKey + "_callback";
        String argPrefix = fnKey + "_arg_";
        String returnVar = fnKey + "_returns";

        IRFunction irFunction = new IRFunction(fn, startJumpLabel, callbackVar, argPrefix, returnVar);
        for (int a = 0; a < fn.getArgCount(); a++) {
            irFunction.addArg(fn.getArgType(a), fn.getArg(a));
        }
        irScopeConfig.irFunctionMap().put(fn, irFunction);
        irScopeConfig.callableNodeMap().put(fnKey, fn);

        if (irScopeConfig.generateComments())
            irScopeConfig.irCode().addSingleLineBlock(new CommentLine("function: " + fnKey, depth));
        irScopeConfig.irCode().addSingleLineBlock((new Jump("always", endJumpLabel, depth)));
        irScopeConfig.irCode().addSingleLineBlock(new Label(startJumpLabel, depth));

        generateScopeRecursive(fn.getScope(), irScopeConfig.copy(), irFunction, depth + 1);

        irScopeConfig.irCode().addSingleLineBlock((new Label(endJumpLabel, depth)));
    }

    private static void generateBranchStatement(IfStatementNode ifs, IRScopeConfig irScopeConfig, IRFunction function, int depth) throws Exception {
        String branchEndLabel = "branch_end_" + irScopeConfig.hashCodeSimplifier().simplifyHash(ifs.hashCode());

        // while loop to go through all the else blocks
        // todo change the true to an actual conditional
        while (true) {
            // loop repeats with a new ifs object
            String currentifHashCode = "" + irScopeConfig.hashCodeSimplifier().simplifyHash(ifs.hashCode());
            String currentIfEndLabel = ifs.nameToken + "_" + currentifHashCode;

            // if current if statement has no else block, just jump to the end
            if (ifs.getElseNode() == null)
                currentIfEndLabel = branchEndLabel;

            // create jump statement (doesn't look pretty)
            IRBlock startJumpBlock = new IRBlock();

            boolean invertCondition = true;
            Jump startJump = createConditionalJump(
                    irScopeConfig,
                    ifs.getExpression(),
                    currentifHashCode,
                    currentIfEndLabel,
                    invertCondition,
                    depth
            );

            startJumpBlock.addLine(startJump);
            irScopeConfig.irCode().irBlocks.add(startJumpBlock);
            generateScopeRecursive(ifs.getScope(), irScopeConfig.copy(), function, depth + 1);

            // if there is an else node, then there must be an always jump to the end
            if (ifs.getElseNode() != null) {
                irScopeConfig.irCode().addSingleLineBlock(new Jump("always", branchEndLabel, depth));
            }

            // end label for the current if statement
            irScopeConfig.irCode().addSingleLineBlock(new Label(currentIfEndLabel, depth));

            // if there is an else node
            if (ifs.getElseNode() != null) {
                ElseNode elseNode = ifs.getElseNode();
                // if it is an else if
                if (elseNode.getIfStatementNode() != null) {
                    ifs = elseNode.getIfStatementNode();

                } else {
                    // if it is just an else
                    generateScopeRecursive(elseNode.getScope(), irScopeConfig.copy(), function, depth + 1);
                    irScopeConfig.irCode().addSingleLineBlock(new Label(branchEndLabel, depth));
                    break;
                }
            } else {
                // if there is no else
                break;
            }
        }
    }

    private static Jump createConditionalJump(IRScopeConfig irScopeConfig, OperationNode exp, String jumpId, String targetLabel, boolean invertCondition, int depth) throws Exception {
        String conditionalVarName = "if_cond_" + jumpId;
        IROperation conditionalOp = addOperationIRBlock(irScopeConfig, exp, conditionalVarName, depth);
        Line lastOperation = conditionalOp.lineList.remove(conditionalOp.lineList.size() - 1);

        Jump startJump;
        if (lastOperation instanceof SetLine setLine) {
            startJump = new Jump(
                    (invertCondition ? opAsMlog(KEY_OP_NOT_EQUAL) : opAsMlog(KEY_OP_EQUALS)) +
                            " " + setLine.getValue() + " 1",
                    targetLabel, depth);

        } else if (lastOperation instanceof BinaryOp bop) {
            startJump = new Jump(
                    (invertCondition ? opAsMlog(negateBooleanOperator(bop.getOp())) : opAsMlog(bop.getOp())) +
                            " " + bop.getLeft() + " " + bop.getRight(),
                    targetLabel, depth);

        } else {
            throw new Exception("Jump conditional must be BinaryOp or Set");
        }

        return startJump;
    }

    private static IROperation addOperationIRBlock(IRScopeConfig irScopeConfig, OperationNode op, String variableName, int depth) throws Exception {
        IROperation opBlock = generateIROperation(irScopeConfig, op, depth);
        irScopeConfig.irCode().irBlocks.add(opBlock);
        // change the name of the last op to the declared var name
        Line lastLine = opBlock.lineList.get(opBlock.lineList.size() - 1);

        if (lastLine instanceof VariableLine variableLine)
            variableLine.setVarName(variableName);

        return opBlock;
    }

    private static IROperation generateIROperation(IRScopeConfig irScopeConfig, OperationNode operationNode, int depth) throws Exception {
        IROperation irOperation = new IROperation();

        generateIROperationHelper(irScopeConfig, operationNode, irOperation, depth);

        return irOperation;
    }

    private static void generateIROperationHelper(IRScopeConfig irScopeConfig, OperationNode operationNode, IROperation irOperation, int depth) throws Exception {
        if (operationNode.isBinary()) {
            boolean leftConstant = operationNode.getLeftSide().isConstant();
            boolean rightConstant = operationNode.getRightSide().isConstant();

            String leftVar = "";
            String rightVar = "";

            if (leftConstant) {
                leftVar = processConstantToken(irScopeConfig, operationNode.getLeftSide().getConstantToken(), depth);
            }

            if (rightConstant) {
                rightVar = processConstantToken(irScopeConfig, operationNode.getRightSide().getConstantToken(), depth);
            }

            // TODO messy
            if (leftConstant && rightConstant) {
                BinaryOp binaryOp = new BinaryOp(operationNode.nameToken.string, operationNode.getOperator(), leftVar, rightVar, depth);
                irOperation.addLine(binaryOp);

            } else {
                BinaryOp binaryOp;

                if (!rightConstant && leftConstant) {
                    binaryOp = new BinaryOp(operationNode.nameToken.string, operationNode.getOperator(), leftVar, operationNode.getRightSide().nameToken.string, depth);
                    generateIROperationHelper(irScopeConfig, operationNode.getRightSide(), irOperation, depth);

                } else if (rightConstant) {
                    binaryOp = new BinaryOp(operationNode.nameToken.string, operationNode.getOperator(), operationNode.getLeftSide().nameToken.string, rightVar, depth);
                    generateIROperationHelper(irScopeConfig, operationNode.getLeftSide(), irOperation, depth);

                } else {
                    binaryOp = new BinaryOp(operationNode.nameToken.string, operationNode.getOperator(), operationNode.getLeftSide().nameToken.string, operationNode.getRightSide().nameToken.string, depth);
                    generateIROperationHelper(irScopeConfig, operationNode.getLeftSide(), irOperation, depth);
                    generateIROperationHelper(irScopeConfig, operationNode.getRightSide(), irOperation, depth);
                }
                irOperation.addLine(binaryOp);
            }
        } else if (operationNode.isConstant()) {
            String constantVar = processConstantToken(irScopeConfig, operationNode.getConstantToken(), depth);
            irOperation.addLine(new SetLine(operationNode.nameToken.string, constantVar, depth));
        }
    }

    private static String processConstantToken(IRScopeConfig irScopeConfig, TypedToken token, int depth) throws Exception {
        if (token instanceof CallerNodeToken callerNodeToken) {
            CallerNode callerNode = callerNodeToken.getNode();
            int callerHashCode = irScopeConfig.hashCodeSimplifier().simplifyHash(callerNodeToken.hashCode());
            CallableNode callableNode = irScopeConfig.callableNodeMap().get(callerNode.signature());

            if (callableNode instanceof FunctionDeclareNode) {
                IRFunction irFunction = generateFunctionCall(irScopeConfig, callerNode, depth);
                String argOutput = irFunction.getReturnVar() + "_" + callerHashCode;
                irScopeConfig.irCode().addSingleLineBlock(new SetLine(argOutput, irFunction.getReturnVar(), depth));
                return argOutput;

            } else if (callableNode instanceof RawTemplateDeclareNode) {
                String argOutput = token.string + "_" + callerHashCode;
                generateRawTemplateInvoke(irScopeConfig, callerNode, argOutput, depth);
                return argOutput;

            } else {
                throw new IllegalArgumentException("Unknown node in CallerNodeToken");
            }

        } else {
            return tokenAsMlog(token);
        }
    }

    protected interface ScopeFunctionConsumer<M, X, Y, Z, W> {
        void apply(M member, X scope, Y config, Z function, W depth) throws Exception;
    }
}

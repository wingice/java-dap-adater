package org.wdap;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.microsoft.java.debug.core.IEvaluatableBreakpoint;
import com.microsoft.java.debug.core.adapter.IEvaluationProvider;
import com.sun.jdi.*;

/**
 * JDI-based expression evaluator for the standalone DAP adapter.
 *
 * Supports:
 *   - Local variable reads: {@code myVar}
 *   - Field access chains: {@code obj.field.subField}
 *   - Method invocation (no-arg and single string arg): {@code obj.method()}, {@code obj.getName()}
 *   - Static field access: {@code com.example.MyClass.FIELD} or {@code MyClass.FIELD}
 *   - Static method calls: {@code MyClass.method()}
 *   - Null literals and comparisons: {@code x == null}, {@code x != null}
 *   - Boolean comparisons: {@code x == y}, {@code x != y}
 *   - Numeric comparisons: {@code x > 0}, {@code x.size() >= 5}
 *   - String literals: {@code "hello"}
 *   - Numeric literals: {@code 42}, {@code 3.14}
 *   - Boolean literals: {@code true}, {@code false}
 *   - instanceof: {@code obj instanceof TypeName}
 *   - Logical operators: {@code &&}, {@code ||}
 *   - Negation: {@code !expr}
 *   - this reference
 *
 * Conditional breakpoints:
 *   The expression from {@code IEvaluatableBreakpoint.getCondition()} is evaluated.
 *   If the result is a BooleanValue, it determines whether to stop.
 *   Any truthy non-null value also stops; null/false continues.
 *
 * Limitations:
 *   - No arithmetic operators (+, -, *, /)
 *   - No array indexing
 *   - No constructor invocations
 *   - No lambda expressions
 *   - No complex casts
 */
public class JdiEvaluationProvider implements IEvaluationProvider {

    private final Set<ThreadReference> evaluatingThreads = ConcurrentHashMap.newKeySet();

    // Pattern for method call: name(args)
    private static final Pattern METHOD_CALL = Pattern.compile("^(\\w+)\\((.*)\\)$");
    // Pattern for comparison: expr op expr
    private static final Pattern COMPARISON = Pattern.compile("^(.+?)\\s*(==|!=|>=|<=|>|<)\\s*(.+)$");
    // Pattern for instanceof: expr instanceof TypeName
    private static final Pattern INSTANCEOF = Pattern.compile("^(.+?)\\s+instanceof\\s+(\\S+)$");
    // Pattern for logical AND/OR (lowest precedence split)
    private static final Pattern LOGICAL_AND = Pattern.compile("^(.+?)\\s*&&\\s*(.+)$");
    private static final Pattern LOGICAL_OR = Pattern.compile("^(.+?)\\s*\\|\\|\\s*(.+)$");
    // Pattern for negation
    private static final Pattern NEGATION = Pattern.compile("^!\\s*(.+)$");
    // String literal
    private static final Pattern STRING_LITERAL = Pattern.compile("^\"(.*)\"$");
    // Numeric literal
    private static final Pattern NUMERIC_LITERAL = Pattern.compile("^-?\\d+(\\.\\d+)?[lLfFdD]?$");

    @Override
    public boolean isInEvaluation(ThreadReference thread) {
        return evaluatingThreads.contains(thread);
    }

    @Override
    public CompletableFuture<Value> evaluate(String expression, ThreadReference thread, int depth) {
        return CompletableFuture.supplyAsync(() -> {
            evaluatingThreads.add(thread);
            try {
                StackFrame frame = thread.frame(depth);
                return evaluateExpression(expression.trim(), thread, frame);
            } catch (EvaluationException e) {
                throw new RuntimeException(e.getMessage(), e);
            } catch (IncompatibleThreadStateException e) {
                throw new RuntimeException("Thread not suspended: " + e.getMessage(), e);
            } catch (Exception e) {
                throw new RuntimeException("Evaluation failed: " + e.getMessage(), e);
            } finally {
                evaluatingThreads.remove(thread);
            }
        });
    }

    @Override
    public CompletableFuture<Value> evaluate(String expression, ObjectReference thisContext, ThreadReference thread) {
        return CompletableFuture.supplyAsync(() -> {
            evaluatingThreads.add(thread);
            try {
                StackFrame frame = thread.frame(0);
                return evaluateExpression(expression.trim(), thread, frame);
            } catch (EvaluationException e) {
                throw new RuntimeException(e.getMessage(), e);
            } catch (Exception e) {
                throw new RuntimeException("Evaluation failed: " + e.getMessage(), e);
            } finally {
                evaluatingThreads.remove(thread);
            }
        });
    }

    @Override
    public CompletableFuture<Value> evaluateForBreakpoint(IEvaluatableBreakpoint breakpoint, ThreadReference thread) {
        return CompletableFuture.supplyAsync(() -> {
            evaluatingThreads.add(thread);
            try {
                String condition = breakpoint.getCondition();
                if (condition == null || condition.isBlank()) {
                    // No condition — always stop (return true)
                    return thread.virtualMachine().mirrorOf(true);
                }
                StackFrame frame = thread.frame(0);
                Value result = evaluateExpression(condition.trim(), thread, frame);
                // Convert result to boolean for conditional breakpoint
                return toBooleanValue(result, thread.virtualMachine());
            } catch (Exception e) {
                // On evaluation failure, default to stopping (safer — shows the error to user)
                System.err.println("[JdiEval] Conditional breakpoint eval failed: " + e.getMessage());
                return thread.virtualMachine().mirrorOf(true);
            } finally {
                evaluatingThreads.remove(thread);
            }
        });
    }

    @Override
    public CompletableFuture<Value> invokeMethod(ObjectReference thisContext, String methodName,
            String methodSignature, Value[] args, ThreadReference thread, boolean invokeSuper) {
        return CompletableFuture.supplyAsync(() -> {
            evaluatingThreads.add(thread);
            try {
                ReferenceType refType = thisContext.referenceType();
                List<Method> methods = methodSignature != null && !methodSignature.isEmpty()
                    ? refType.methodsByName(methodName, methodSignature)
                    : refType.methodsByName(methodName);
                Method method = methods.stream()
                    .findFirst()
                    .orElseThrow(() -> new EvaluationException(
                        "Method not found: " + methodName +
                        (methodSignature != null ? methodSignature : "") +
                        " on " + refType.name()));
                List<Value> argList = args != null ? Arrays.asList(args) : Collections.emptyList();
                int options = ObjectReference.INVOKE_SINGLE_THREADED;
                return thisContext.invokeMethod(thread, method, argList, options);
            } catch (EvaluationException e) {
                throw new RuntimeException(e.getMessage(), e);
            } catch (Exception e) {
                throw new RuntimeException("Method invocation failed: " + e.getMessage(), e);
            } finally {
                evaluatingThreads.remove(thread);
            }
        });
    }

    @Override
    public void clearState(ThreadReference thread) {
        evaluatingThreads.remove(thread);
    }

    // ─── Expression Evaluator ─────────────────────────────────────────────

    private Value evaluateExpression(String expr, ThreadReference thread, StackFrame frame)
            throws EvaluationException {
        if (expr.isEmpty()) {
            throw new EvaluationException("Empty expression");
        }

        // Strip outer parentheses
        if (expr.startsWith("(") && expr.endsWith(")") && isBalancedParens(expr)) {
            return evaluateExpression(expr.substring(1, expr.length() - 1).trim(), thread, frame);
        }

        // Logical OR (lowest precedence)
        Matcher m = matchAtTopLevel(expr, LOGICAL_OR);
        if (m != null) {
            Value left = evaluateExpression(m.group(1).trim(), thread, frame);
            if (isTruthy(left)) {
                return thread.virtualMachine().mirrorOf(true);
            }
            Value right = evaluateExpression(m.group(2).trim(), thread, frame);
            return thread.virtualMachine().mirrorOf(isTruthy(right));
        }

        // Logical AND
        m = matchAtTopLevel(expr, LOGICAL_AND);
        if (m != null) {
            Value left = evaluateExpression(m.group(1).trim(), thread, frame);
            if (!isTruthy(left)) {
                return thread.virtualMachine().mirrorOf(false);
            }
            Value right = evaluateExpression(m.group(2).trim(), thread, frame);
            return thread.virtualMachine().mirrorOf(isTruthy(right));
        }

        // Negation
        m = NEGATION.matcher(expr);
        if (m.matches()) {
            Value val = evaluateExpression(m.group(1).trim(), thread, frame);
            return thread.virtualMachine().mirrorOf(!isTruthy(val));
        }

        // instanceof
        m = INSTANCEOF.matcher(expr);
        if (m.matches()) {
            Value left = evaluateExpression(m.group(1).trim(), thread, frame);
            String typeName = m.group(2).trim();
            boolean result = isInstanceOf(left, typeName, thread.virtualMachine());
            return thread.virtualMachine().mirrorOf(result);
        }

        // Comparison operators
        m = matchComparison(expr);
        if (m != null) {
            String leftExpr = m.group(1).trim();
            String op = m.group(2);
            String rightExpr = m.group(3).trim();
            Value left = evaluateExpression(leftExpr, thread, frame);
            Value right = evaluateExpression(rightExpr, thread, frame);
            boolean result = compare(left, right, op, thread.virtualMachine());
            return thread.virtualMachine().mirrorOf(result);
        }

        // Literals
        if ("null".equals(expr)) {
            return null;
        }
        if ("true".equals(expr)) {
            return thread.virtualMachine().mirrorOf(true);
        }
        if ("false".equals(expr)) {
            return thread.virtualMachine().mirrorOf(false);
        }

        // String literal
        m = STRING_LITERAL.matcher(expr);
        if (m.matches()) {
            return thread.virtualMachine().mirrorOf(m.group(1));
        }

        // Numeric literal
        m = NUMERIC_LITERAL.matcher(expr);
        if (m.matches()) {
            return parseNumericLiteral(expr, thread.virtualMachine());
        }

        // this
        if ("this".equals(expr)) {
            return frame.thisObject();
        }

        // Dotted access chain: a.b.c or a.b.method() or Class.FIELD
        if (expr.contains(".")) {
            return evaluateDottedExpression(expr, thread, frame);
        }

        // Method call on implicit this: method() or method(arg)
        m = METHOD_CALL.matcher(expr);
        if (m.matches()) {
            String methodName = m.group(1);
            String argsStr = m.group(2).trim();
            ObjectReference thisObj = frame.thisObject();
            if (thisObj == null) {
                // Static context — try static method on declaring type
                ReferenceType declType = frame.location().declaringType();
                return invokeStaticMethod(declType, methodName, argsStr, thread, frame);
            }
            return invokeMethodOnObject(thisObj, methodName, argsStr, thread, frame);
        }

        // Simple local variable or parameter
        try {
            LocalVariable var = frame.visibleVariableByName(expr);
            if (var != null) {
                return frame.getValue(var);
            }
        } catch (AbsentInformationException e) {
            // No debug info — fall through
        }

        // Try as a field of 'this'
        ObjectReference thisObj = frame.thisObject();
        if (thisObj != null) {
            Field field = thisObj.referenceType().fieldByName(expr);
            if (field != null) {
                return thisObj.getValue(field);
            }
        }

        // Try as a static field of the declaring class
        ReferenceType declType = frame.location().declaringType();
        Field field = declType.fieldByName(expr);
        if (field != null) {
            return declType.getValue(field);
        }

        // Try as a fully-qualified class name (for Class.class references)
        List<ReferenceType> types = thread.virtualMachine().classesByName(expr);
        if (!types.isEmpty()) {
            return types.get(0).classObject();
        }

        throw new EvaluationException("Cannot resolve: " + expr);
    }

    private Value evaluateDottedExpression(String expr, ThreadReference thread, StackFrame frame)
            throws EvaluationException {
        // Split into segments, respecting method call parentheses
        List<String> segments = splitDotted(expr);
        if (segments.isEmpty()) {
            throw new EvaluationException("Invalid dotted expression: " + expr);
        }

        // Try progressively longer prefixes as class names (for static access)
        Value current = null;
        ReferenceType staticType = null;
        int startIdx = 0;

        // First, try the first segment as a local/field/this
        String first = segments.get(0);
        Matcher methodMatch = METHOD_CALL.matcher(first);

        if ("this".equals(first)) {
            current = frame.thisObject();
            startIdx = 1;
        } else if (methodMatch.matches()) {
            // Method call as first segment
            ObjectReference thisObj = frame.thisObject();
            if (thisObj != null) {
                current = invokeMethodOnObject(thisObj, methodMatch.group(1),
                    methodMatch.group(2).trim(), thread, frame);
            } else {
                ReferenceType declType = frame.location().declaringType();
                current = invokeStaticMethod(declType, methodMatch.group(1),
                    methodMatch.group(2).trim(), thread, frame);
            }
            startIdx = 1;
        } else {
            // Try as local variable
            try {
                LocalVariable var = frame.visibleVariableByName(first);
                if (var != null) {
                    current = frame.getValue(var);
                    startIdx = 1;
                }
            } catch (AbsentInformationException e) {
                // fall through
            }

            // Try as field of 'this'
            if (current == null) {
                ObjectReference thisObj = frame.thisObject();
                if (thisObj != null) {
                    Field field = thisObj.referenceType().fieldByName(first);
                    if (field != null) {
                        current = thisObj.getValue(field);
                        startIdx = 1;
                    }
                }
            }

            // Try progressively longer prefixes as fully-qualified class names
            if (current == null) {
                for (int prefixLen = 1; prefixLen <= segments.size(); prefixLen++) {
                    String className = String.join(".", segments.subList(0, prefixLen));
                    // Skip if this segment looks like a method call
                    if (METHOD_CALL.matcher(segments.get(prefixLen - 1)).matches()) {
                        break;
                    }
                    List<ReferenceType> types = thread.virtualMachine().classesByName(className);
                    if (!types.isEmpty()) {
                        staticType = types.get(0);
                        startIdx = prefixLen;
                    }
                }
            }
        }

        // If we found a static type but no instance, start accessing statics
        if (current == null && staticType != null && startIdx < segments.size()) {
            String seg = segments.get(startIdx);
            Matcher mm = METHOD_CALL.matcher(seg);
            if (mm.matches()) {
                current = invokeStaticMethod(staticType, mm.group(1), mm.group(2).trim(), thread, frame);
            } else {
                Field field = staticType.fieldByName(seg);
                if (field != null) {
                    current = staticType.getValue(field);
                } else {
                    throw new EvaluationException("No field '" + seg + "' on " + staticType.name());
                }
            }
            startIdx++;
        }

        if (current == null && staticType == null) {
            throw new EvaluationException("Cannot resolve first segment: " + first);
        }

        // Walk remaining segments
        for (int i = startIdx; i < segments.size(); i++) {
            String seg = segments.get(i);
            if (current == null) {
                throw new EvaluationException("NullPointerException: accessing '" + seg + "' on null");
            }
            if (!(current instanceof ObjectReference)) {
                throw new EvaluationException(
                    "Cannot dereference primitive value for '" + seg + "'");
            }
            ObjectReference obj = (ObjectReference) current;
            Matcher mm = METHOD_CALL.matcher(seg);
            if (mm.matches()) {
                current = invokeMethodOnObject(obj, mm.group(1), mm.group(2).trim(), thread, frame);
            } else {
                // Field access
                Field field = obj.referenceType().fieldByName(seg);
                if (field != null) {
                    current = obj.getValue(field);
                } else {
                    // Try method with no parens as getter (common in debugger usage)
                    throw new EvaluationException(
                        "No field '" + seg + "' on " + obj.referenceType().name() +
                        ". Did you mean " + seg + "()?");
                }
            }
        }

        return current;
    }

    // ─── Method Invocation ────────────────────────────────────────────────

    private Value invokeMethodOnObject(ObjectReference obj, String methodName, String argsStr,
            ThreadReference thread, StackFrame frame) throws EvaluationException {
        try {
            ReferenceType refType = obj.referenceType();
            List<Method> methods = refType.methodsByName(methodName);
            if (methods.isEmpty()) {
                throw new EvaluationException(
                    "No method '" + methodName + "' on " + refType.name());
            }

            List<Value> args = parseArguments(argsStr, thread, frame);
            // Find best matching method by argument count
            Method method = findMatchingMethod(methods, args);
            if (method == null) {
                throw new EvaluationException(
                    "No overload of '" + methodName + "' on " + refType.name() +
                    " accepts " + args.size() + " argument(s)");
            }

            return obj.invokeMethod(thread, method, args, ObjectReference.INVOKE_SINGLE_THREADED);
        } catch (EvaluationException e) {
            throw e;
        } catch (InvocationException e) {
            ObjectReference exception = e.exception();
            String exType = exception.referenceType().name();
            // Try to get exception message
            try {
                List<Method> getMsgMethods = exception.referenceType().methodsByName("getMessage");
                if (!getMsgMethods.isEmpty()) {
                    Value msgVal = exception.invokeMethod(thread, getMsgMethods.get(0),
                        Collections.emptyList(), ObjectReference.INVOKE_SINGLE_THREADED);
                    String msg = msgVal != null ? ((StringReference) msgVal).value() : "";
                    throw new EvaluationException(exType + ": " + msg);
                }
            } catch (EvaluationException ee) {
                throw ee;
            } catch (Exception ignored) {}
            throw new EvaluationException("InvocationException: " + exType);
        } catch (Exception e) {
            throw new EvaluationException("Failed to invoke " + methodName + ": " + e.getMessage());
        }
    }

    private Value invokeStaticMethod(ReferenceType type, String methodName, String argsStr,
            ThreadReference thread, StackFrame frame) throws EvaluationException {
        try {
            List<Method> methods = type.methodsByName(methodName);
            methods = methods.stream().filter(Method::isStatic).toList();
            if (methods.isEmpty()) {
                throw new EvaluationException(
                    "No static method '" + methodName + "' on " + type.name());
            }

            List<Value> args = parseArguments(argsStr, thread, frame);
            Method method = findMatchingMethod(methods, args);
            if (method == null) {
                throw new EvaluationException(
                    "No static overload of '" + methodName + "' on " + type.name() +
                    " accepts " + args.size() + " argument(s)");
            }

            if (type instanceof ClassType classType) {
                return classType.invokeMethod(thread, method, args,
                    ObjectReference.INVOKE_SINGLE_THREADED);
            }
            throw new EvaluationException("Cannot invoke static method on " + type.getClass().getSimpleName());
        } catch (EvaluationException e) {
            throw e;
        } catch (InvocationException e) {
            throw new EvaluationException("InvocationException: " + e.exception().referenceType().name());
        } catch (Exception e) {
            throw new EvaluationException("Failed to invoke static " + methodName + ": " + e.getMessage());
        }
    }

    private Method findMatchingMethod(List<Method> methods, List<Value> args) {
        // Exact match on argument count
        for (Method m : methods) {
            try {
                if (m.argumentTypeNames().size() == args.size()) {
                    return m;
                }
            } catch (Exception e) {
                // Skip methods we can't introspect
            }
        }
        // Varargs: method with fewer declared args might accept more via varargs
        // For simplicity, if there's exactly one candidate, use it
        if (methods.size() == 1) {
            return methods.get(0);
        }
        return null;
    }

    private List<Value> parseArguments(String argsStr, ThreadReference thread, StackFrame frame)
            throws EvaluationException {
        if (argsStr.isEmpty()) {
            return Collections.emptyList();
        }
        // Split by commas, respecting parentheses and strings
        List<String> argExprs = splitArguments(argsStr);
        List<Value> result = new ArrayList<>();
        for (String argExpr : argExprs) {
            result.add(evaluateExpression(argExpr.trim(), thread, frame));
        }
        return result;
    }

    // ─── Comparison Logic ─────────────────────────────────────────────────

    private boolean compare(Value left, Value right, String op, VirtualMachine vm)
            throws EvaluationException {
        // Null comparisons
        if (left == null && right == null) {
            return "==".equals(op);
        }
        if (left == null || right == null) {
            return switch (op) {
                case "==" -> false;
                case "!=" -> true;
                default -> throw new EvaluationException("Cannot compare null with " + op);
            };
        }

        // Numeric comparisons
        if (isNumeric(left) && isNumeric(right)) {
            double l = toDouble(left);
            double r = toDouble(right);
            return switch (op) {
                case "==" -> l == r;
                case "!=" -> l != r;
                case ">" -> l > r;
                case "<" -> l < r;
                case ">=" -> l >= r;
                case "<=" -> l <= r;
                default -> throw new EvaluationException("Unknown operator: " + op);
            };
        }

        // Boolean comparisons
        if (left instanceof BooleanValue && right instanceof BooleanValue) {
            boolean l = ((BooleanValue) left).value();
            boolean r = ((BooleanValue) right).value();
            return switch (op) {
                case "==" -> l == r;
                case "!=" -> l != r;
                default -> throw new EvaluationException("Cannot use " + op + " on booleans");
            };
        }

        // Object reference equality
        if (left instanceof ObjectReference && right instanceof ObjectReference) {
            if ("==".equals(op)) {
                return ((ObjectReference) left).uniqueID() == ((ObjectReference) right).uniqueID();
            }
            if ("!=".equals(op)) {
                return ((ObjectReference) left).uniqueID() != ((ObjectReference) right).uniqueID();
            }
            // Try .equals() for == on strings
            if (left instanceof StringReference && right instanceof StringReference) {
                String l = ((StringReference) left).value();
                String r = ((StringReference) right).value();
                return switch (op) {
                    case "==" -> l.equals(r);
                    case "!=" -> !l.equals(r);
                    default -> throw new EvaluationException("Cannot use " + op + " on strings");
                };
            }
        }

        throw new EvaluationException("Cannot compare " + left.type().name() + " " + op + " " + right.type().name());
    }

    private boolean isInstanceOf(Value value, String typeName, VirtualMachine vm) {
        if (value == null) return false;
        if (!(value instanceof ObjectReference objRef)) return false;

        ReferenceType actualType = objRef.referenceType();
        // Check exact match or parent types
        return isAssignableTo(actualType, typeName);
    }

    private boolean isAssignableTo(ReferenceType type, String targetName) {
        if (type.name().equals(targetName) || type.name().endsWith("." + targetName)) {
            return true;
        }
        if (type instanceof ClassType classType) {
            // Check superclass
            ClassType superClass = classType.superclass();
            if (superClass != null && isAssignableTo(superClass, targetName)) {
                return true;
            }
            // Check interfaces
            for (InterfaceType iface : classType.allInterfaces()) {
                if (iface.name().equals(targetName) || iface.name().endsWith("." + targetName)) {
                    return true;
                }
            }
        }
        if (type instanceof InterfaceType ifaceType) {
            for (InterfaceType superIface : ifaceType.superinterfaces()) {
                if (isAssignableTo(superIface, targetName)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ─── Utility Methods ──────────────────────────────────────────────────

    private Value toBooleanValue(Value value, VirtualMachine vm) {
        if (value instanceof BooleanValue) {
            return value;
        }
        return vm.mirrorOf(isTruthy(value));
    }

    private boolean isTruthy(Value value) {
        if (value == null) return false;
        if (value instanceof BooleanValue) return ((BooleanValue) value).value();
        if (value instanceof IntegerValue) return ((IntegerValue) value).value() != 0;
        if (value instanceof LongValue) return ((LongValue) value).value() != 0;
        if (value instanceof ShortValue) return ((ShortValue) value).value() != 0;
        if (value instanceof ByteValue) return ((ByteValue) value).value() != 0;
        if (value instanceof CharValue) return ((CharValue) value).value() != 0;
        if (value instanceof FloatValue) return ((FloatValue) value).value() != 0;
        if (value instanceof DoubleValue) return ((DoubleValue) value).value() != 0;
        // Non-null object reference is truthy
        return value instanceof ObjectReference;
    }

    private boolean isNumeric(Value v) {
        return v instanceof IntegerValue || v instanceof LongValue || v instanceof ShortValue
            || v instanceof ByteValue || v instanceof FloatValue || v instanceof DoubleValue
            || v instanceof CharValue;
    }

    private double toDouble(Value v) {
        if (v instanceof IntegerValue) return ((IntegerValue) v).value();
        if (v instanceof LongValue) return ((LongValue) v).value();
        if (v instanceof ShortValue) return ((ShortValue) v).value();
        if (v instanceof ByteValue) return ((ByteValue) v).value();
        if (v instanceof FloatValue) return ((FloatValue) v).value();
        if (v instanceof DoubleValue) return ((DoubleValue) v).value();
        if (v instanceof CharValue) return ((CharValue) v).value();
        return 0;
    }

    private Value parseNumericLiteral(String expr, VirtualMachine vm) {
        String lower = expr.toLowerCase();
        if (lower.endsWith("l")) {
            return vm.mirrorOf(Long.parseLong(expr.substring(0, expr.length() - 1)));
        }
        if (lower.endsWith("f")) {
            return vm.mirrorOf(Float.parseFloat(expr.substring(0, expr.length() - 1)));
        }
        if (lower.endsWith("d")) {
            return vm.mirrorOf(Double.parseDouble(expr.substring(0, expr.length() - 1)));
        }
        if (expr.contains(".")) {
            return vm.mirrorOf(Double.parseDouble(expr));
        }
        long val = Long.parseLong(expr);
        if (val >= Integer.MIN_VALUE && val <= Integer.MAX_VALUE) {
            return vm.mirrorOf((int) val);
        }
        return vm.mirrorOf(val);
    }

    // ─── Parsing Helpers ──────────────────────────────────────────────────

    /**
     * Split a dotted expression into segments, respecting parentheses.
     * e.g., "a.b.method(x.y).c" → ["a", "b", "method(x.y)", "c"]
     */
    private List<String> splitDotted(String expr) {
        List<String> segments = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (c == '(' || c == '[') depth++;
            else if (c == ')' || c == ']') depth--;
            else if (c == '.' && depth == 0) {
                segments.add(expr.substring(start, i));
                start = i + 1;
            }
        }
        if (start < expr.length()) {
            segments.add(expr.substring(start));
        }
        return segments;
    }

    /**
     * Split comma-separated arguments, respecting parentheses and strings.
     */
    private List<String> splitArguments(String argsStr) {
        List<String> args = new ArrayList<>();
        int depth = 0;
        boolean inString = false;
        int start = 0;
        for (int i = 0; i < argsStr.length(); i++) {
            char c = argsStr.charAt(i);
            if (c == '"' && (i == 0 || argsStr.charAt(i - 1) != '\\')) {
                inString = !inString;
            }
            if (!inString) {
                if (c == '(' || c == '[') depth++;
                else if (c == ')' || c == ']') depth--;
                else if (c == ',' && depth == 0) {
                    args.add(argsStr.substring(start, i));
                    start = i + 1;
                }
            }
        }
        if (start < argsStr.length()) {
            args.add(argsStr.substring(start));
        }
        return args;
    }

    private boolean isBalancedParens(String expr) {
        // Check if the outer parens actually wrap the whole expression
        int depth = 0;
        for (int i = 0; i < expr.length() - 1; i++) {
            char c = expr.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            if (depth == 0 && i < expr.length() - 2) return false;
        }
        return depth == 1;
    }

    /**
     * Match comparison at top level (not inside parens).
     */
    private Matcher matchComparison(String expr) {
        // Find comparison operators at depth 0
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (c == '"' && (i == 0 || expr.charAt(i - 1) != '\\')) {
                inString = !inString;
                continue;
            }
            if (inString) continue;
            if (c == '(' || c == '[') { depth++; continue; }
            if (c == ')' || c == ']') { depth--; continue; }
            if (depth > 0) continue;

            // Check for two-char operators first
            if (i + 1 < expr.length()) {
                String two = expr.substring(i, i + 2);
                if (two.equals("==") || two.equals("!=") || two.equals(">=") || two.equals("<=")) {
                    String left = expr.substring(0, i).trim();
                    String right = expr.substring(i + 2).trim();
                    if (!left.isEmpty() && !right.isEmpty()) {
                        // Return a simple 3-group pseudo-matcher via a pattern that always matches
                        String combined = left + "\u0000" + two + "\u0000" + right;
                        Matcher m = Pattern.compile("^(.*?)\u0000(.*?)\u0000(.*)$", Pattern.DOTALL).matcher(combined);
                        if (m.matches()) return m;
                    }
                }
            }
            // Single char operators (> or <) not followed by =
            if ((c == '>' || c == '<') && (i + 1 >= expr.length() || expr.charAt(i + 1) != '=')) {
                String left = expr.substring(0, i).trim();
                String right = expr.substring(i + 1).trim();
                if (!left.isEmpty() && !right.isEmpty()) {
                    String combined = left + "\u0000" + c + "\u0000" + right;
                    Matcher m = Pattern.compile("^(.*?)\u0000(.*?)\u0000(.*)$", Pattern.DOTALL).matcher(combined);
                    if (m.matches()) return m;
                }
            }
        }
        return null;
    }

    /**
     * Match logical operators at top level only.
     */
    private Matcher matchAtTopLevel(String expr, Pattern pattern) {
        // Simple approach: use regex but verify the match point is at depth 0
        Matcher m = pattern.matcher(expr);
        if (m.matches()) {
            // Verify the operator is at depth 0
            String left = m.group(1);
            int depth = 0;
            boolean inStr = false;
            for (int i = 0; i < left.length(); i++) {
                char c = left.charAt(i);
                if (c == '"' && (i == 0 || left.charAt(i - 1) != '\\')) inStr = !inStr;
                if (!inStr) {
                    if (c == '(' || c == '[') depth++;
                    else if (c == ')' || c == ']') depth--;
                }
            }
            if (depth == 0) return m;
        }
        return null;
    }

    // ─── Exception Type ───────────────────────────────────────────────────

    private static class EvaluationException extends Exception {
        EvaluationException(String message) {
            super(message);
        }
    }
}

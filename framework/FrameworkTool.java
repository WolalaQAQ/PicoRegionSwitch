import com.android.tools.smali.baksmali.BaksmaliOptions;
import com.android.tools.smali.baksmali.Adaptors.ClassDefinition;
import com.android.tools.smali.baksmali.Adaptors.MethodDefinition;
import com.android.tools.smali.baksmali.formatter.BaksmaliWriter;
import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.builder.Label;
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation;
import com.android.tools.smali.dexlib2.builder.instruction.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.debug.DebugItem;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;
import com.android.tools.smali.dexlib2.immutable.*;
import com.android.tools.smali.dexlib2.immutable.reference.*;
import com.android.tools.smali.dexlib2.immutable.value.ImmutableEncodedValueFactory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Offline single-method rewrite and decoded, reference-resolved full-dex audit. */
public final class FrameworkTool {
    static final String PMS = "Lcom/android/server/pm/PackageManagerService;";
    static final String BEFORE = "Landroid/content/pm/PackageParser$Package;";
    static final String AFTER = "Landroid/content/pm/PackageInfoLite;";
    static final String HELPER = "Lcom/android/server/pm/PicoRegionDowngradeGate;";
    static final String TARGET = PMS + "->checkDowngrade(" + BEFORE + AFTER + ")V";
    static final int PREFIX_INSNS = 5;
    static final int PREFIX_UNITS = 9;

    static String id(MethodReference m) {
        StringBuilder b = new StringBuilder(m.getDefiningClass()).append("->").append(m.getName()).append('(');
        for (CharSequence p : m.getParameterTypes()) b.append(p);
        return b.append(')').append(m.getReturnType()).toString();
    }

    static DexFile load(String path) throws IOException {
        byte[] header = Files.readAllBytes(Paths.get(path));
        int version = Integer.parseInt(new String(header, 4, 3, StandardCharsets.US_ASCII));
        return DexFileFactory.loadDexFile(new File(path), Opcodes.forDexVersion(version));
    }

    static TreeMap<String, ClassDef> classes(DexFile dex) {
        TreeMap<String, ClassDef> result = new TreeMap<>();
        for (ClassDef c : dex.getClasses()) require(result.put(c.getType(), c) == null, "duplicate class");
        return result;
    }

    static TreeMap<String, Method> methods(ClassDef c) {
        TreeMap<String, Method> result = new TreeMap<>();
        for (Method m : c.getMethods()) require(result.put(id(m), m) == null, "duplicate method");
        return result;
    }

    static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 32 || Character.isSurrogate(c)) b.append(String.format("\\u%04x", (int)c));
            else b.append(c);
        }
        return b.append('"').toString();
    }

    static void getters(Class<?> type, TreeMap<String, java.lang.reflect.Method> result) {
        if (type == null) return;
        if (type.isInterface() && type.getName().startsWith("com.android.tools.smali.dexlib2.iface.")) {
            for (java.lang.reflect.Method m : type.getMethods()) {
                if (m.getParameterCount() == 0 && m.getName().startsWith("get")) result.put(m.getName(), m);
            }
        }
        for (Class<?> iface : type.getInterfaces()) getters(iface, result);
        getters(type.getSuperclass(), result);
    }

    static TreeMap<String, Object> properties(Object value, String... exclusions) throws Exception {
        Set<String> excluded = new HashSet<>(Arrays.asList(exclusions));
        TreeMap<String, java.lang.reflect.Method> getters = new TreeMap<>();
        getters(value.getClass(), getters);
        require(!getters.isEmpty(), "No interface coverage: " + value.getClass());
        TreeMap<String, Object> result = new TreeMap<>();
        for (Map.Entry<String, java.lang.reflect.Method> item : getters.entrySet()) {
            if (!excluded.contains(item.getKey())) result.put(item.getKey(), item.getValue().invoke(value));
        }
        return result;
    }

    // All dexlib interface getters are included. Sets are sorted; ordered data stay ordered.
    static String json(Object value) throws Exception {
        if (value == null) return "null";
        if (value instanceof CharSequence || value instanceof Character || value instanceof Enum<?>) {
            return quote(value.toString());
        }
        if (value instanceof Number || value instanceof Boolean) return quote(value.toString());
        if (value instanceof Map<?, ?>) {
            List<String> items = new ArrayList<>();
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>)value).entrySet()) sorted.put(e.getKey().toString(), e.getValue());
            for (Map.Entry<String, Object> e : sorted.entrySet()) items.add(quote(e.getKey()) + ":" + json(e.getValue()));
            return "{" + String.join(",", items) + "}";
        }
        if (value instanceof Iterable<?>) {
            List<String> items = new ArrayList<>();
            for (Object item : (Iterable<?>)value) items.add(json(item));
            if (value instanceof Set<?>) Collections.sort(items);
            return "[" + String.join(",", items) + "]";
        }
        return json(properties(value));
    }

    static String digest(String value) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format("%02x", b & 255));
        return out.toString();
    }

    static void writeJson(String path, Object value) throws Exception {
        Files.write(Paths.get(path), (json(value) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }

    static List<Instruction> instructions(MethodImplementation impl) {
        List<Instruction> result = new ArrayList<>();
        for (Instruction i : impl.getInstructions()) result.add(i);
        return result;
    }

    static final class Body {
        final List<Instruction> all;
        final Map<Integer, Integer> index = new HashMap<>();
        final List<Integer> addresses = new ArrayList<>();
        final Map<Integer, List<Integer>> switchBases = new HashMap<>();
        final int skip;

        Body(MethodImplementation impl, int skip) {
            this.skip = skip;
            all = instructions(impl);
            int address = 0;
            for (int n = 0; n < all.size(); n++) {
                addresses.add(address);
                if (n >= skip) index.put(address, n - skip);
                address += all.get(n).getCodeUnits();
            }
            index.put(address, all.size() - skip);
            for (int n = skip; n < all.size(); n++) {
                Instruction insn = all.get(n);
                if (insn.getOpcode() == Opcode.PACKED_SWITCH || insn.getOpcode() == Opcode.SPARSE_SWITCH) {
                    int payload = addresses.get(n) + ((OffsetInstruction)insn).getCodeOffset();
                    switchBases.computeIfAbsent(payload, x -> new ArrayList<>()).add(addresses.get(n));
                }
            }
        }

        int label(int address) {
            Integer result = index.get(address);
            require(result != null, "address not on instruction boundary: " + address);
            return result;
        }

        Map<String, Object> instruction(int n) throws Exception {
            Instruction insn = all.get(n);
            TreeMap<String, Object> result = properties(insn, "getCodeUnits", "getCodeOffset", "getSwitchElements");
            if (insn instanceof OffsetInstruction) {
                result.put("targetInstruction", label(addresses.get(n) + ((OffsetInstruction)insn).getCodeOffset()));
            }
            if (insn instanceof SwitchPayload) {
                List<Object> targets = new ArrayList<>();
                List<Integer> bases = switchBases.get(addresses.get(n));
                require(bases != null, "orphan switch payload");
                for (int base : bases) {
                    List<Object> cases = new ArrayList<>();
                    for (SwitchElement e : ((SwitchPayload)insn).getSwitchElements()) {
                        cases.add(Arrays.asList(e.getKey(), label(base + e.getOffset())));
                    }
                    targets.add(Arrays.asList(label(base), cases));
                }
                result.put("switchTargets", targets);
            }
            return result;
        }

        Map<String, Object> canonical(MethodImplementation impl) throws Exception {
            List<Object> code = new ArrayList<>();
            for (int n = skip; n < all.size(); n++) code.add(instruction(n));
            List<Object> tries = new ArrayList<>();
            for (TryBlock<? extends ExceptionHandler> t : impl.getTryBlocks()) {
                List<Object> handlers = new ArrayList<>();
                for (ExceptionHandler h : t.getExceptionHandlers()) {
                    handlers.add(Arrays.asList(h.getExceptionType(), label(h.getHandlerCodeAddress())));
                }
                tries.add(Arrays.asList(label(t.getStartCodeAddress()),
                        label(t.getStartCodeAddress() + t.getCodeUnitCount()), handlers));
            }
            List<Object> debug = new ArrayList<>();
            for (DebugItem d : impl.getDebugItems()) {
                Map<String, Object> item = properties(d, "getCodeAddress");
                item.put("instructionAddress", label(d.getCodeAddress()));
                debug.add(item);
            }
            Map<String, Object> result = new TreeMap<>();
            result.put("registers", impl.getRegisterCount());
            result.put("instructions", code);
            result.put("tryBlocks", tries);
            result.put("debugItems", debug);
            return result;
        }
    }

    static Map<String, Object> classMeta(ClassDef c) throws Exception {
        Map<String, Object> result = properties(c, "getMethods", "getDirectMethods", "getVirtualMethods",
                "getFields", "getStaticFields", "getInstanceFields");
        List<Object> fields = new ArrayList<>();
        for (Field f : c.getStaticFields()) {
            Map<String, Object> meta = properties(f);
            // DEX trailing static defaults may be omitted without changing their value.
            if (f.getInitialValue() == null) {
                meta.put("getInitialValue", ImmutableEncodedValueFactory.defaultValueForType(f.getType()));
            }
            fields.add(Arrays.asList("static", meta));
        }
        for (Field f : c.getInstanceFields()) fields.add(Arrays.asList("instance", properties(f)));
        result.put("fields", fields);
        List<String> direct = new ArrayList<>();
        for (Method m : c.getDirectMethods()) direct.add(id(m));
        List<String> virtual = new ArrayList<>();
        for (Method m : c.getVirtualMethods()) virtual.add(id(m));
        result.put("directMethods", direct);
        result.put("virtualMethods", virtual);
        return result;
    }

    static String methodCanonical(Method m, int skip) throws Exception {
        Map<String, Object> result = properties(m, "getImplementation");
        if (m.getImplementation() != null) {
            result.put("body", new Body(m.getImplementation(), skip).canonical(m.getImplementation()));
        }
        return json(result);
    }

    static Map<String, Object> stats(Method m) {
        Map<String, Object> out = new TreeMap<>();
        out.put("method", id(m));
        out.put("accessFlags", m.getAccessFlags());
        MethodImplementation impl = m.getImplementation();
        if (impl != null) {
            List<Instruction> insns = instructions(impl);
            int units = 0, debug = 0;
            for (Instruction i : insns) units += i.getCodeUnits();
            for (DebugItem ignored : impl.getDebugItems()) debug++;
            out.put("registers", impl.getRegisterCount());
            out.put("instructions", insns.size());
            out.put("codeUnits", units);
            out.put("tryBlocks", impl.getTryBlocks().size());
            out.put("debugItems", debug);
        }
        return out;
    }

    static void smali(ClassDef c, Method m, String path) throws Exception {
        BaksmaliOptions options = new BaksmaliOptions();
        options.apiLevel = 29;
        options.debugInfo = true;
        options.accessorComments = false;
        options.codeOffsets = true;
        StringWriter text = new StringWriter();
        BaksmaliWriter writer = new BaksmaliWriter(text);
        new MethodDefinition(new ClassDefinition(options, c), m, m.getImplementation()).writeTo(writer);
        writer.flush();
        Files.write(Paths.get(path), text.toString().getBytes(StandardCharsets.UTF_8));
    }

    static void inspect(String path, String output) throws Exception {
        DexFile dex = load(path);
        Map<String, Object> report = new TreeMap<>();
        List<Object> refs = new ArrayList<>();
        int count = 0, codeCount = 0, tryCount = 0, debugCount = 0;
        for (ClassDef c : dex.getClasses()) {
            for (Method m : c.getMethods()) {
                count++;
                if (id(m).equals(TARGET)) {
                    report.put("target", stats(m));
                    smali(c, m, output + ".target.smali");
                }
                if (m.getImplementation() == null) continue;
                tryCount += m.getImplementation().getTryBlocks().size();
                for (DebugItem ignored : m.getImplementation().getDebugItems()) debugCount++;
                int address = 0;
                for (Instruction i : m.getImplementation().getInstructions()) {
                    codeCount++;
                    if (i instanceof ReferenceInstruction) {
                        Reference ref = ((ReferenceInstruction)i).getReference();
                        if (ref instanceof MethodReference) {
                            String dest = id((MethodReference)ref);
                            if (dest.equals(TARGET) || dest.startsWith(PMS + "->access$2700(")) {
                                refs.add(Arrays.asList(id(m), address, dest));
                            }
                        }
                    }
                    address += i.getCodeUnits();
                }
            }
        }
        report.put("classes", dex.getClasses().size());
        report.put("methods", count);
        report.put("instructions", codeCount);
        report.put("tryBlocks", tryCount);
        report.put("debugItems", debugCount);
        report.put("callers", refs);
        writeJson(output + ".json", report);
        System.out.println(json(report));
    }

    static Method patch(Method m) {
        MethodImplementation original = m.getImplementation();
        require(m.getAccessFlags() == 10, "expected private static access=10");
        int p0 = original.getRegisterCount() - 2;
        require(p0 > 0 && p0 < 16, "p0 must fit iget-object and leave scratch v0");
        MutableMethodImplementation body = new MutableMethodImplementation(original);
        Label originalEntry = body.newLabelForIndex(0);
        List<com.android.tools.smali.dexlib2.builder.BuilderInstruction> prefix = Arrays.asList(
                new BuilderInstruction22c(Opcode.IGET_OBJECT, 0, p0,
                        new ImmutableFieldReference(BEFORE, "packageName", "Ljava/lang/String;")),
                new BuilderInstruction35c(Opcode.INVOKE_STATIC, 1, 0, 0, 0, 0, 0,
                        new ImmutableMethodReference(HELPER, "allow", Collections.singletonList("Ljava/lang/String;"), "Z")),
                new BuilderInstruction11x(Opcode.MOVE_RESULT, 0),
                new BuilderInstruction21t(Opcode.IF_EQZ, 0, originalEntry),
                new BuilderInstruction10x(Opcode.RETURN_VOID));
        for (int n = prefix.size() - 1; n >= 0; n--) body.addInstruction(0, prefix.get(n));
        require(body.getRegisterCount() == original.getRegisterCount(), "register count drift");
        return new ImmutableMethod(m.getDefiningClass(), m.getName(), m.getParameters(), m.getReturnType(),
                m.getAccessFlags(), m.getAnnotations(), m.getHiddenApiRestrictions(), body);
    }

    static void rewrite(String input, String helperPath, String output, boolean patched) throws Exception {
        DexFile dex = load(input);
        List<ClassDef> out = new ArrayList<>();
        int touched = 0;
        for (ClassDef c : dex.getClasses()) {
            require(!c.getType().equals(HELPER), "helper already exists");
            if (patched && c.getType().equals(PMS)) {
                List<Method> direct = new ArrayList<>();
                for (Method m : c.getDirectMethods()) {
                    if (id(m).equals(TARGET)) { direct.add(patch(m)); touched++; }
                    else direct.add(m);
                }
                out.add(new ImmutableClassDef(c.getType(), c.getAccessFlags(), c.getSuperclass(), c.getInterfaces(),
                        c.getSourceFile(), c.getAnnotations(), c.getStaticFields(), c.getInstanceFields(),
                        direct, c.getVirtualMethods()));
            } else out.add(c);
        }
        if (patched) {
            DexFile helper = load(helperPath);
            require(helper.getClasses().size() == 1, "helper dex must contain exactly one class (no stubs)");
            ClassDef only = helper.getClasses().iterator().next();
            require(only.getType().equals(HELPER), "unexpected helper class");
            require((only.getAccessFlags() & 1) != 0, "helper must be public");
            out.add(only);
            require(touched == 1, "target count " + touched);
        }
        DexFileFactory.writeDexFile(output, new ImmutableDexFile(dex.getOpcodes(), out));
        System.out.println("PATCHED_METHODS=" + touched);
        System.out.println("RESULT=success");
    }

    static void validatePrefix(Method before, Method after) throws Exception {
        List<Instruction> p = instructions(after.getImplementation());
        Opcode[] expected = {Opcode.IGET_OBJECT, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN_VOID};
        for (int n = 0; n < expected.length; n++) require(p.get(n).getOpcode() == expected[n], "prefix opcode");
        TwoRegisterInstruction get = (TwoRegisterInstruction)p.get(0);
        require(get.getRegisterA() == 0 && get.getRegisterB() == before.getImplementation().getRegisterCount() - 2, "prefix registers");
        require(((FieldReference)((ReferenceInstruction)p.get(0)).getReference()).equals(
                new ImmutableFieldReference(BEFORE, "packageName", "Ljava/lang/String;")), "packageName reference");
        require(id((MethodReference)((ReferenceInstruction)p.get(1)).getReference()).equals(
                HELPER + "->allow(Ljava/lang/String;)Z"), "gate reference");
        FiveRegisterInstruction call = (FiveRegisterInstruction)p.get(1);
        require(call.getRegisterCount() == 1 && call.getRegisterC() == 0, "gate arguments");
        require(((OneRegisterInstruction)p.get(2)).getRegisterA() == 0, "move-result register");
        require(((OneRegisterInstruction)p.get(3)).getRegisterA() == 0, "branch register");
        int units = 0;
        for (int n = 0; n < PREFIX_INSNS; n++) units += p.get(n).getCodeUnits();
        require(units == PREFIX_UNITS && ((OffsetInstruction)p.get(3)).getCodeOffset() == 3, "prefix target");
    }

    static Map<String, Object> validateLocalStructure(Method m) {
        MethodImplementation impl = m.getImplementation();
        Body body = new Body(impl, 0);
        int total = impl.getRegisterCount(), branchCount = 0, resultCount = 0;
        for (int n = 0; n < body.all.size(); n++) {
            Instruction insn = body.all.get(n);
            if (insn instanceof FiveRegisterInstruction) {
                FiveRegisterInstruction i = (FiveRegisterInstruction)insn;
                int[] registers = {i.getRegisterC(), i.getRegisterD(), i.getRegisterE(), i.getRegisterF(), i.getRegisterG()};
                for (int k = 0; k < i.getRegisterCount(); k++) require(registers[k] < total, "invoke register out of range");
            } else if (insn instanceof RegisterRangeInstruction) {
                RegisterRangeInstruction i = (RegisterRangeInstruction)insn;
                require(i.getStartRegister() + i.getRegisterCount() <= total, "invoke range out of bounds");
            } else {
                if (insn instanceof OneRegisterInstruction) require(((OneRegisterInstruction)insn).getRegisterA() < total, "vA out of range");
                if (insn instanceof TwoRegisterInstruction) require(((TwoRegisterInstruction)insn).getRegisterB() < total, "vB out of range");
                if (insn instanceof ThreeRegisterInstruction) require(((ThreeRegisterInstruction)insn).getRegisterC() < total, "vC out of range");
            }
            String op = insn.getOpcode().name();
            if (op.startsWith("MOVE_RESULT")) {
                require(n > 0, "result at method start");
                String previous = body.all.get(n - 1).getOpcode().name();
                require(previous.startsWith("INVOKE_") || previous.startsWith("FILLED_NEW_ARRAY"), "result without producer");
                resultCount++;
            }
            if (insn instanceof OffsetInstruction) {
                int target = body.label(body.addresses.get(n) + ((OffsetInstruction)insn).getCodeOffset());
                require(target < body.all.size(), "branch past method end");
                require(!body.all.get(target).getOpcode().name().startsWith("MOVE_RESULT"), "branch into result");
                branchCount++;
            }
        }
        Map<String, Object> result = new TreeMap<>();
        result.put("method", id(m)); result.put("registerOperandsInRange", true);
        result.put("branchesOnBoundariesNotMoveResult", branchCount);
        result.put("moveResultsHaveAdjacentProducer", resultCount);
        return result;
    }

    static void audit(String aPath, String bPath, String output, boolean patched) throws Exception {
        TreeMap<String, ClassDef> a = classes(load(aPath)), b = classes(load(bPath));
        Set<String> added = new TreeSet<>(b.keySet()); added.removeAll(a.keySet());
        Set<String> removed = new TreeSet<>(a.keySet()); removed.removeAll(b.keySet());
        require(removed.isEmpty(), "removed classes");
        require(added.equals(patched ? Collections.singleton(HELPER) : Collections.emptySet()), "added classes " + added);
        int classCount = 0, methodCount = 0, instructions = 0, tries = 0, debug = 0;
        List<Object> differences = new ArrayList<>();
        List<Object> staticDefaultStorageChanges = new ArrayList<>();
        Map<String, Object> targetStats = new TreeMap<>();
        List<Object> localStructure = new ArrayList<>();
        try (BufferedWriter manifest = Files.newBufferedWriter(Paths.get(output + ".methods.tsv"), StandardCharsets.UTF_8)) {
            manifest.write("kind\tidentity\toriginal_sha256\tcandidate_sha256\tcomparison\n");
            for (String name : a.keySet()) {
                ClassDef ac = a.get(name), bc = b.get(name);
                Iterator<? extends Field> af = ac.getStaticFields().iterator();
                Iterator<? extends Field> bf = bc.getStaticFields().iterator();
                while (af.hasNext() && bf.hasNext()) {
                    Field fa = af.next(), fb = bf.next();
                    if (!json(fa.getInitialValue()).equals(json(fb.getInitialValue()))) {
                        Map<String, Object> storage = new TreeMap<>();
                        storage.put("field", fa.toString());
                        storage.put("original", fa.getInitialValue());
                        storage.put("candidate", fb.getInitialValue());
                        staticDefaultStorageChanges.add(storage);
                    }
                }
                String am = json(classMeta(ac)), bm = json(classMeta(bc));
                if (!am.equals(bm)) {
                    differences.add(Arrays.asList("class_metadata", name));
                    Files.write(Paths.get(output + ".diff-" + differences.size() + ".original.json"), am.getBytes(StandardCharsets.UTF_8));
                    Files.write(Paths.get(output + ".diff-" + differences.size() + ".candidate.json"), bm.getBytes(StandardCharsets.UTF_8));
                }
                manifest.write("class\t" + name + "\t" + digest(am) + "\t" + digest(bm) + "\tmetadata\n");
                classCount++;
                TreeMap<String, Method> aa = methods(ac), bb = methods(bc);
                require(aa.keySet().equals(bb.keySet()), "method set drift " + name);
                for (String key : aa.keySet()) {
                    Method ma = aa.get(key), mb = bb.get(key);
                    boolean target = patched && key.equals(TARGET);
                    String ca = methodCanonical(ma, 0), cb = methodCanonical(mb, target ? PREFIX_INSNS : 0);
                    if (!ca.equals(cb)) {
                        differences.add(Arrays.asList("method_semantics_or_metadata", key));
                        Files.write(Paths.get(output + ".diff-" + differences.size() + ".original.json"), ca.getBytes(StandardCharsets.UTF_8));
                        Files.write(Paths.get(output + ".diff-" + differences.size() + ".candidate.json"), cb.getBytes(StandardCharsets.UTF_8));
                    }
                    manifest.write("method\t" + key + "\t" + digest(ca) + "\t" + digest(cb) + "\t" + (target ? "original-suffix" : "full") + "\n");
                    if (key.equals(TARGET)) {
                        targetStats.put("original", stats(ma)); targetStats.put("candidate", stats(mb));
                        smali(ac, ma, output + ".target.original.smali");
                        smali(bc, mb, output + ".target.candidate.smali");
                        if (target) {
                            validatePrefix(ma, mb);
                            localStructure.add(validateLocalStructure(mb));
                        }
                    }
                    methodCount++;
                    MethodImplementation impl = ma.getImplementation();
                    if (impl != null) {
                        instructions += FrameworkTool.instructions(impl).size();
                        tries += impl.getTryBlocks().size();
                        for (DebugItem ignored : impl.getDebugItems()) debug++;
                    }
                }
            }
        }
        List<Object> helperStats = new ArrayList<>();
        if (patched) {
            require((b.get(HELPER).getAccessFlags() & 1) != 0, "helper not public");
            require(methods(b.get(HELPER)).size() == 2, "unexpected helper methods");
            for (Method m : b.get(HELPER).getMethods()) {
                if (m.getName().equals("allow")) {
                    require(id(m).equals(HELPER + "->allow(Ljava/lang/String;)Z") && m.getAccessFlags() == 9,
                            "helper interface must be public static boolean allow(String)");
                }
                helperStats.add(stats(m));
                methodCanonical(m, 0); // Decode every helper instruction, branch, try and debug address.
                localStructure.add(validateLocalStructure(m));
                smali(b.get(HELPER), m, output + ".helper-" + m.getName().replace('<', '_').replace('>', '_') + ".smali");
            }
        }
        Map<String, Object> report = new TreeMap<>();
        report.put("classesCompared", classCount); report.put("methodsCompared", methodCount);
        report.put("instructionsCompared", instructions); report.put("tryBlocksCompared", tries);
        report.put("debugItemsCompared", debug); report.put("addedClasses", added); report.put("removedClasses", removed);
        report.put("differences", differences); report.put("target", targetStats); report.put("helperMethods", helperStats);
        report.put("staticDefaultStorageChanges", staticDefaultStorageChanges);
        report.put("localStructure", localStructure);
        report.put("result", differences.isEmpty() ? "success" : "failure");
        writeJson(output + ".json", report);
        System.out.println(json(report));
        require(differences.isEmpty(), "audit found differences");
        System.out.println("RESULT=success");
    }

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "inspect": inspect(args[1], args[2]); break;
            case "control": rewrite(args[1], args[2], args[3], false); break;
            case "patch": rewrite(args[1], args[2], args[3], true); break;
            case "audit-control": audit(args[1], args[2], args[3], false); break;
            case "audit-patch": audit(args[1], args[2], args[3], true); break;
            case "metadata":
                writeJson(args[3], classMeta(classes(load(args[1])).get(args[2])));
                break;
            default: throw new IllegalArgumentException(args[0]);
        }
    }
}

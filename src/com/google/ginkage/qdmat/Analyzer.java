package com.google.ginkage.qdmat;

import org.eclipse.mat.SnapshotException;
import org.eclipse.mat.parser.internal.SnapshotFactory;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.snapshot.model.IArray;
import org.eclipse.mat.snapshot.model.IClass;
import org.eclipse.mat.snapshot.model.IObject;
import org.eclipse.mat.snapshot.model.IObjectArray;
import org.eclipse.mat.snapshot.model.IPrimitiveArray;
import org.eclipse.mat.snapshot.model.NamedReference;
import org.eclipse.mat.util.VoidProgressListener;

import java.io.File;
import java.util.*;

public class Analyzer {

    private static class ObjectNode {
        public IObject object;
        public Map<ObjectNode, String> outRefs;
        public Set<ObjectNode> inRefs;
        public Map<ObjectNode, String> retains;
        public double size;
        public int selfSize;

        // root node
        ObjectNode() {
            object = null;
            outRefs = new HashMap<>();
            inRefs = null;
            size = 0;
            selfSize = 0;
        }

        // object node
        ObjectNode(IObject object, ObjectNode parent, String name) {
            this.object = object;
            this.outRefs = new HashMap<>();
            this.inRefs = new HashSet<>();
            this.retains = new HashMap<>();

            IClass clazz = object.getClazz();
            this.selfSize = clazz.getHeapSizePerInstance();
            if (clazz.isArrayType()) {
                int elementSize = ((object instanceof IObjectArray) ? 4 :
                        IPrimitiveArray.ELEMENT_SIZE[((IPrimitiveArray)object).getType()]);
                this.selfSize = elementSize * ((IArray)object).getLength();
            }
            this.size = this.selfSize;

            parent.link(this, name);
        }

        void retain(ObjectNode ret, String name) {
            if (!retains.containsKey(ret) && !name.contains("ClassLoader")) {
                retains.put(ret, name);
            }
        }

        // "A -> B" folded into "AB" using the outbound reference from A.
        ObjectNode(ObjectNode a, ObjectNode b) {
            String name = a.outRefs.get(b);

            this.object = a.object;
            this.outRefs = new HashMap<>();
            this.inRefs = new HashSet<>();
            this.size = a.size  + b.size;
            this.selfSize = a.selfSize;
            this.retains = new HashMap<>();
            for (ObjectNode ret : a.retains.keySet()) {
                this.retain(ret, a.retains.get(ret));
            }
            for (ObjectNode ret : b.retains.keySet()) {
                this.retain(ret, combine(name, b.retains.get(ret)));
            }
            this.retains.put(b, name); // The new object was created using A as a prototype, so only add B.

            // Cleanup to avoid concurrent modification
            Set<ObjectNode> aInRefs = a.inRefs;
            Set<ObjectNode> bInRefs = b.inRefs;
            Map<ObjectNode, String> aOutRefs = a.outRefs;
            Map<ObjectNode, String> bOutRefs = b.outRefs;
            a.inRefs = new HashSet<>();
            b.inRefs = new HashSet<>();
            a.outRefs = new HashMap<>();
            b.outRefs = new HashMap<>();

            Map<ObjectNode, String> inRefNames = new HashMap<>();

            // Remove all old links
            for (ObjectNode ref : aInRefs) {
                inRefNames.put(ref, ref.outRefs.get(a));
                ref.outRefs.remove(a);
            }
            for (ObjectNode ref : aOutRefs.keySet()) {
                ref.inRefs.remove(a);
            }
            for (ObjectNode ref : bInRefs) {
                if (!inRefNames.containsKey(ref)) {
                    // Prefer the referencing object's link name.
                    inRefNames.put(ref, ref.outRefs.get(b));
                }
                ref.outRefs.remove(b);
            }
            for (ObjectNode ref : bOutRefs.keySet()) {
                ref.inRefs.remove(b);
            }

            // Link again, being aware of self-linking.
            for (ObjectNode ref : aInRefs) {
                if (ref != b) {
                    ref.link(this, inRefNames.get(ref));
                }
            }
            for (ObjectNode ref : aOutRefs.keySet()) {
                if (ref != b) {
                    this.link(ref, aOutRefs.get(ref));
                }
            }
            for (ObjectNode ref : bInRefs) {
                if (ref != a) {
                    ref.link(this, inRefNames.get(ref));
                }
            }
            for (ObjectNode ref : bOutRefs.keySet()) {
                if (ref != a) {
                    this.link(ref, combine(name, bOutRefs.get(ref)));
                }
            }
        }

        public void link(ObjectNode child, String name) {
            if (child == this) {
                return;
            }
            if (!this.outRefs.containsKey(child)) {
                this.outRefs.put(child, name);
            }
            child.inRefs.add(this);
        }

        public String unlink(ObjectNode child) {
            String name = this.outRefs.get(child);
            this.outRefs.remove(child);
            child.inRefs.remove(this);
            return name;
        }

        public static String combine(String parent, String child) {
            if (child.startsWith("[")) {
                return parent + child;
            } else {
                return parent + "." + child;
            }
        }

        public void fold(ObjectNode child) {
            String name = unlink(child);

            if (child.outRefs.size() > 0) {
                Map<ObjectNode, String> childRefs = child.outRefs;
                child.outRefs = new HashMap<>();
                for (ObjectNode ref : childRefs.keySet()) {
                    child.unlink(ref);
                    this.link(ref, combine(name, childRefs.get(ref)));
                }
            }

            this.size += child.size;

            for (ObjectNode ret : child.retains.keySet()) {
                this.retain(ret, combine(name, child.retains.get(ret)));
            }
            this.retain(child, name);
        }

        @Override
        public int hashCode() {
            return (object == null ? 0 : object.hashCode());
        }
    }

    private static ObjectNode loadFile(File dumpFile) {
        ObjectNode root = new ObjectNode();

        SnapshotFactory factory = new SnapshotFactory();
        Map<String, String> argsMap = Collections.emptyMap();
        VoidProgressListener listener = new VoidProgressListener();

        try {
            ISnapshot snapshot = factory.openSnapshot(dumpFile, argsMap, listener);
            Collection<IClass> refClasses =
                    snapshot.getClassesByName("com.google.android.clockwork.home.HomeApplication", false);

            Map<IObject, ObjectNode> visited = new HashMap<>();
            Queue<IObject> queue = new LinkedList<>();

            for (IClass refClass : refClasses) {
                int[] instanceIds = refClass.getObjectIds();

                for (int instanceId : instanceIds) {
                    IObject instance = snapshot.getObject(instanceId);
                    visited.put(instance, new ObjectNode(instance, root, "#"));
                    queue.add(instance);
                }
            }

            while (!queue.isEmpty()) {
                IObject instance = queue.remove();
                ObjectNode parent = visited.get(instance);

                List<NamedReference> refs = instance.getOutboundReferences();
                for (NamedReference ref : refs) {
                    String refName = ref.getName();
                    IObject field = (IObject) instance.resolveValue(refName);
                    if (field == null) {
                        continue;
                    }

                    IClass clazz = field.getClazz();
                    String type = clazz.getName();
                    if (type.equals("java.lang.ref.WeakReference") ||
                            type.equals("java.lang.ref.FinalizerReference") ||
                            type.equals("java.lang.reflect.ArtMethod")) {
                        continue;
                    }

                    if (visited.containsKey(field)) {
                        parent.link(visited.get(field), refName);
                    } else {
                        visited.put(field, new ObjectNode(field, parent, refName));
                        queue.add(field);
                    }
                }
            }
        } catch (SnapshotException e) {
            e.printStackTrace();
        }

        return root;
    }

    private static Set<ObjectNode> flattenGraph(ObjectNode root) {
        Set<ObjectNode> graph = new HashSet<>();
        Queue<ObjectNode> queue = new LinkedList<>();

        // No need to mark as visited: it doesn't have inbound links.
        queue.add(root);

        while (!queue.isEmpty()) {
            ObjectNode instance = queue.remove();

            for (ObjectNode field : instance.outRefs.keySet()) {
                if (!graph.contains(field)) {
                    graph.add(field);
                    queue.add(field);
                }
            }
        }

        return graph;
    }

    // Hanging nodes, which only have inbound references.
    // Soft folding means distributing the size between referencing objects when there's more than one inbound link.
    private static void foldLeaves(Set<ObjectNode> graph, boolean soft) {
        Queue<ObjectNode> queue = new LinkedList<>();

        for (ObjectNode node : graph) {
            if (node.outRefs.size() == 0 && (soft || node.inRefs.size() == 1)) {
                queue.add(node);
            }
        }

        while (!queue.isEmpty()) {
            ObjectNode node = queue.remove();

            Set<ObjectNode> parents = node.inRefs;
            node.inRefs = new HashSet<>();
            int denom = parents.size();

            // node doesn't have any outbound references, so transferring those is not required.
            for (ObjectNode parent : parents) {
                String name = parent.outRefs.get(node);
                parent.outRefs.remove(node);
                parent.size += node.size / denom;

                // For hard-folding we're going to add selfSize as if it's the object's own size.
                // This is particularly useful e.g. when folding char[] to String, and byte[] to Bitmap.
                if (soft) {
                    for (ObjectNode ret : node.retains.keySet()) {
                        parent.retain(ret, ObjectNode.combine(name, node.retains.get(ret)));
                    }
                    parent.retain(node, name);
                } else {
                    parent.selfSize += node.selfSize;
                }

                if (parent.outRefs.size() == 0 && (soft || node.inRefs.size() == 1)) {
                    queue.add(parent);
                }
            }

            graph.remove(node);
            if (!soft) {
                node.selfSize = 0;
            }
        }
    }

    // For the tricky double-linked circular lists.
    private static void foldLinkedLists(Set<ObjectNode> graph) {
        Queue<ObjectNode> queue = new LinkedList<>();

        for (ObjectNode node : graph) {
            String name = node.object.getClazz().getName();
            for (ObjectNode ref : node.outRefs.keySet()) {
                String refName = ref.object.getClazz().getName();
                if (refName.equals(name)) {
                    // Found a linked list entry... We'll get a lot of those, so watch out for duplicates.
                    queue.add(node);
                    break;
                }
            }
        }

        while (!queue.isEmpty()) {
            ObjectNode node = queue.remove();
            if (!graph.contains(node)) { // We might have removed that one earlier...
                continue;
            }

            ObjectNode next = null;
            String name = node.object.getClazz().getName();
            for (ObjectNode ref : node.outRefs.keySet()) {
                String refName = ref.object.getClazz().getName();
                if (refName.equals(name)) {
                    next = ref;
                    break;
                }
            }

            if (next == null) {
                // Probably unlinked already.
                continue;
            }

            // We have two nodes which we must make into one.
            ObjectNode combo = new ObjectNode(node, next);
            graph.remove(node);
            graph.remove(next);
            graph.add(combo);

            // Check if the new node is still a part of linked list.
            for (ObjectNode ref : combo.outRefs.keySet()) {
                String refName = ref.object.getClazz().getName();
                if (refName.equals(name)) {
                    // Found a linked list entry... We'll get a lot of those, so watch out for duplicates.
                    queue.add(combo);
                    break;
                }
            }
        }
    }

    // To fold single incoming references by an owning class, which is usually a container or another helper.
    private static void foldHelpers(Set<ObjectNode> graph, Set<String> components) {
        Queue<ObjectNode> queue = new LinkedList<>();

        for (ObjectNode node : graph) {
            if (node.inRefs.size() == 1) {
                // More like a "potential" parent, we'll check that right away.
                ObjectNode parent = node.inRefs.iterator().next();

                String name = node.object.getClazz().getName();
                String pname = parent.object.getClazz().getName();

                if (name.contains("$") || name.contains("[]") || name.equals(pname) ||
                        !components.contains(name) || name.startsWith("java.") || name.startsWith("android.")) {
                    // The only inbound reference is from a container, utility class or an owner class. Fold it.
                    // Intermediate classes are folded as well.
                    queue.add(node);
                }
            }
        }

        // For every case of A -> A$B -> A$B$C, we have both connections in the queue.
        while (!queue.isEmpty()) {
            ObjectNode node = queue.remove();
            ObjectNode parent = node.inRefs.iterator().next(); // exactly one
            parent.fold(node);
            graph.remove(node);
        }
    }

    // To fold the "A -> A$B" links, even if its inRefs is not a single link (a combination of two previous methods).
    private static void foldSubclass(Set<ObjectNode> graph) {
        Queue<ObjectNode> queue = new LinkedList<>();

        for (ObjectNode node : graph) {
            String name = node.object.getClazz().getName();
            for (ObjectNode ref : node.outRefs.keySet()) {
                String refName = ref.object.getClazz().getName();
                if (refName.startsWith(name + "$")) {
                    // Found a class that references its own subclass (one time)...
                    boolean single = true;
                    for (ObjectNode inRef : ref.inRefs) {
                        if (inRef != node && inRef.object.getClazz().getName().equals(name)) {
                            single = false;
                            break;
                        }
                    }
                    if (single) {
                        queue.add(node);
                        break;
                    }
                }
            }
        }

        while (!queue.isEmpty()) {
            ObjectNode node = queue.remove();

            // Gather all the subclass references.
            Set<ObjectNode> refs = new HashSet<>();
            String name = node.object.getClazz().getName();
            for (ObjectNode ref : node.outRefs.keySet()) {
                String refName = ref.object.getClazz().getName();
                if (refName.startsWith(name + "$")) {
                    refs.add(ref);
                }
            }

            // Just like with a queue, but using concurrent modification...
            while (!refs.isEmpty()) {
                ObjectNode child = refs.iterator().next();
                refs.remove(child);
                if (!graph.contains(child)) {
                    continue;
                }

                // We have two nodes which we must make into one.
                ObjectNode combo = new ObjectNode(node, child);
                graph.remove(node);
                graph.remove(child);
                graph.add(combo);

                // If the child node had any linked-list-style references, add them to the kill-list.
                for (ObjectNode ref : combo.outRefs.keySet()) {
                    String refName = ref.object.getClazz().getName();
                    if (refName.startsWith(name + "$")) {
                        refs.add(ref);
                    }
                }

                node = combo;
            }
        }
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: qdmat <dump>.hprof");
            return;
        }

        File dumpFile = new File(args[0]);
        if (!dumpFile.exists()) {
            System.out.println("File " + args[0] + " not found");
            return;
        }

        ObjectNode root = loadFile(dumpFile);
        Set<ObjectNode> graph = flattenGraph(root);

        double totalSize = 0;
        final Map<String, Integer> typeCount = new HashMap<>();
        for (ObjectNode node : graph) {
            String name = node.object.getClazz().getName();
            if (!typeCount.containsKey(name)) {
                typeCount.put(name, 1);
            } else {
                typeCount.put(name, typeCount.get(name) + 1);
            }
            totalSize += node.size;
        }
        System.out.println("Total size: " + Math.round(totalSize));

        Set<String> components = new HashSet<>();
        for (String name : typeCount.keySet()) {
            if (typeCount.get(name) == 1) {
                components.add(name);
            }
        }

        // First pass: hard-folding leaves.
        int prevSize = -1;
        while (graph.size() != prevSize) {
            prevSize = graph.size();
            foldLeaves(graph, false);
//            foldSubclass(graph);
            foldLinkedLists(graph);
            foldHelpers(graph, components);
        }

        // Second pass: soft-folding leaves.
        prevSize = -1;
        while (graph.size() != prevSize) {
            prevSize = graph.size();
            foldLeaves(graph, true);
//            foldSubclass(graph);
            foldLinkedLists(graph);
            foldHelpers(graph, components);
        }

        SortedSet<ObjectNode> nodes = new TreeSet<>(new Comparator<ObjectNode>(){
            public int compare(ObjectNode a, ObjectNode b){
                return (a.size < b.size ? 1 : -1);
            }
        });
        nodes.addAll(graph);

        totalSize = 0;
        for (ObjectNode node : nodes) {
            int retainSize = node.selfSize;
            for (ObjectNode ref : node.retains.keySet()) {
                retainSize += ref.selfSize;
            }

            System.out.println(node.object.getClazz().getName() +
                    ", size=" + Math.round(node.size) + " / " + node.selfSize +
                    ", inRefs=" + node.inRefs.size() + ", outRefs=" + node.outRefs.size() +
                    ", retains=" + retainSize + " (" + node.retains.size() + " objects)");
/*
            if (node.inRefs.size() < 5) {
                System.out.println("  Inbound references:");
                for (ObjectNode ref : node.inRefs) {
                    System.out.println("    " + ref.object.getClazz().getName() + "." + ref.outRefs.get(node));
                }
            }

            if (node.outRefs.size() < 5) {
                System.out.println("  Outbound references:");
                for (ObjectNode ref : node.outRefs.keySet()) {
                    System.out.println("    " + ref.object.getClazz().getName() + " " + node.outRefs.get(ref));
                }
            }

            // com.google.android.clockwork.home.cuecard.CueCardPageIndicator
            // com.google.android.clockwork.now.NowRowAdapter
            // com.android.clockwork.gestures.detector.MCAGestureClassifier
            // com.google.android.clockwork.mediacontrols.MediaControlReceiver
            if (node.object.getClazz().getName().equals("com.google.android.clockwork.home.cuecard.CueCardPageIndicator")) {
                for (ObjectNode ref : node.retains.keySet()) {
                    System.out.println("    " + ref.object.getClazz().getName() + ", size=" + ref.selfSize + " :: " + node.retains.get(ref));
                }
            }

            for (ObjectNode ref : node.retains) {
                if (ref.selfSize > 4096) {
                    System.out.println("    " + ref.object.getClazz().getName() + ", size=" + ref.selfSize);
                }
            }

            if (node.object.getClazz().getName().equals("android.app.LoadedApk")) {
                System.out.println("  Referenced by:");
                for (ObjectNode ref : node.inRefs) {
                    System.out.println("    " + ref.object.getClazz().getName());
                }
            }

          String name = node.object.getClassSpecificName();
            if (name != null) {
                System.out.println("  data: \"" + name + "\"");
            }

            if (node.outRefs.size() == 1) {
                ObjectNode ref = node.outRefs.iterator().next();
                System.out.println("  outRef: " + ref.object.getClazz().getName()
                        + " with " + ref.inRefs.size() + " refs");
            }
*/
            totalSize += node.size;

        }
        System.out.println("Total size: " + Math.round(totalSize));

        final Map<String, Double> typeSize = new HashMap<>();
        for (ObjectNode node : graph) {
            String name = node.object.getClazz().getName();
            if (!typeSize.containsKey(name)) {
                typeSize.put(name, node.size);
            } else {
                typeSize.put(name, typeSize.get(name) + node.size);
            }
        }

        SortedSet<String> types = new TreeSet<>(new Comparator<String>(){
            public int compare(String a, String b){
                return ((typeSize.get(a) < typeSize.get(b)) ? 1 : -1);
            }
        });
        types.addAll(typeSize.keySet());

        for (String type : types) {
            double size = typeSize.get(type);
            System.out.printf("%s => %d (%.2f%%)\n", type, Math.round(size), size * 100 / totalSize);
        }
    }

}

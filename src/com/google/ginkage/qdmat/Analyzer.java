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
        public ObjectNode folder;
        public Map<ObjectNode, String> outRefs;
        public Set<ObjectNode> inRefs;
        public Map<ObjectNode, String> retains;
        public double size;
        public int retSize;
        public int retCount;

        // root node
        ObjectNode() {
            object = null;
            folder = null;
            outRefs = new HashMap<>();
            inRefs = null;
            size = 0;
            retSize = 0;
            retCount = 0;
        }

        // object node
        ObjectNode(IObject object, ObjectNode parent, String name) {
            this.object = object;
            this.folder = null;
            this.outRefs = new HashMap<>();
            this.inRefs = new HashSet<>();
            this.retains = new HashMap<>();

            IClass clazz = object.getClazz();
            this.retSize = clazz.getHeapSizePerInstance();
            if (clazz.isArrayType()) {
                int elementSize = ((object instanceof IObjectArray) ? 4 :
                        IPrimitiveArray.ELEMENT_SIZE[((IPrimitiveArray)object).getType()]);
                this.retSize = elementSize * ((IArray)object).getLength();
            }
            this.size = this.retSize;
            this.retCount = 0;

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
            this.folder = a.folder;
            this.outRefs = new HashMap<>();
            this.inRefs = new HashSet<>();
            this.size = a.size + b.size;
            this.retSize = a.retSize;
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
                    Object ofield = instance.resolveValue(refName);
                    if (!(ofield instanceof IObject)) {
                        continue;
                    }

                    IObject field = (IObject) ofield;
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

                for (ObjectNode ret : node.retains.keySet()) {
                    parent.retain(ret, ObjectNode.combine(name, node.retains.get(ret)));
                }
                parent.retain(node, name);

                if (!soft) {
                    node.folder = parent;
                }

                if (parent.outRefs.size() == 0 && (soft || parent.inRefs.size() == 1)) {
                    queue.add(parent);
                }
            }

            graph.remove(node);
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

        System.out.println("Nodes before reduction: " + graph.size());

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
            foldLinkedLists(graph);
            foldHelpers(graph, components);
        }

        // Second pass: soft-folding leaves.
        prevSize = -1;
        while (graph.size() != prevSize) {
            prevSize = graph.size();
            foldLeaves(graph, true);
            foldLinkedLists(graph);
            foldHelpers(graph, components);
        }

        SortedSet<ObjectNode> nodes = new TreeSet<>(new Comparator<ObjectNode>(){
            public int compare(ObjectNode a, ObjectNode b){
                return (a.size < b.size ? 1 : -1);
            }
        });
        nodes.addAll(graph);

        System.out.println("Nodes after reduction: " + graph.size());

        Map<ObjectNode, Integer> retCount = new HashMap<>();
        for (ObjectNode node : nodes) {
            for (ObjectNode ret : node.retains.keySet()) {
                if (retCount.containsKey(ret)) {
                    retCount.put(ret, retCount.get(ret) + 1);
                } else {
                    retCount.put(ret, 1);
                }
            }
        }

        for (ObjectNode node : nodes) {
            for (ObjectNode ret : node.retains.keySet()) {
                if (retCount.get(ret) == 1) {
                    node.retSize += ret.retSize;
                    node.retCount++;
                }
            }
        }

        totalSize = 0;
        for (ObjectNode node : nodes) {
            System.out.println(node.object.getClazz().getName() +
                    ", weighed_size=" + Math.round(node.size) +
                    ", inRefs=" + node.inRefs.size() + ", outRefs=" + node.outRefs.size() +
                    ", retain_size=" + node.retSize + " (" + node.retCount + " objects)");
/*
            // com.google.android.clockwork.home.cuecard.CueCardPageIndicator
            // com.google.android.clockwork.now.NowRowAdapter
            // com.android.clockwork.gestures.detector.MCAGestureClassifier
            // com.google.android.clockwork.mediacontrols.MediaControlReceiver
            if (node.object.getClazz().getName().equals("com.google.android.clockwork.stream.bridger.NotificationBridger")) {
                System.out.println("  Outbound references:");
                for (ObjectNode ref : node.outRefs.keySet()) {
                    System.out.println("    " + ref.object.getClazz().getName() + " " + node.outRefs.get(ref));
                    System.out.println("      size=" + Math.round(ref.size) +
                            ", inRefs=" + ref.inRefs.size() + ", outRefs=" + ref.outRefs.size() +
                            ", retains=" + ref.retSize + " (" + ref.retCount + " objects)");
                }
                System.out.println("  Retained objects:");
                for (ObjectNode ret : node.retains.keySet()) {
                    System.out.println("    " + ret.object.getClazz().getName() + ", size=" + ret.retSize + " :: " + node.retains.get(ret));
                    String name = ret.object.getClassSpecificName();
                    if (name != null) {
                        System.out.println("      data: \"" + name + "\"");
                    }
                }
            }

            for (ObjectNode ret : node.retains.keySet()) {
                if (ret.retSize > 4096 && retCount.get(ret) == 1) {
                    System.out.println("    " + ret.object.getClazz().getName() + ", size=" + ret.retSize);
                    System.out.println("        " + node.retains.get(ret));
                    String name = ret.object.getClassSpecificName();
                    if (name != null) {
                        System.out.println("    data: \"" + name + "\"");
                    }
                }
            }
*/
            totalSize += node.size;

        }
        System.out.println("Total size: " + Math.round(totalSize));

        final Map<String, Double> typeSize = new HashMap<>();
        final Map<String, Integer> retSize = new HashMap<>();
        for (ObjectNode node : graph) {
            String name = node.object.getClazz().getName();
            if (!typeSize.containsKey(name)) {
                typeSize.put(name, node.size);
            } else {
                typeSize.put(name, typeSize.get(name) + node.size);
            }
            if (!retSize.containsKey(name)) {
                retSize.put(name, node.retSize);
            } else {
                retSize.put(name, retSize.get(name) + node.retSize);
            }
        }

        SortedSet<String> types = new TreeSet<>(new Comparator<String>(){
            public int compare(String a, String b){
                return ((typeSize.get(a) < typeSize.get(b)) ? 1 : -1);
            }
        });
        types.addAll(typeSize.keySet());

        System.out.println("Components count: " + types.size());

        for (String type : types) {
            double size = typeSize.get(type);
            System.out.printf("%s => %d (%.2f%%) / %d\n",
                    type, Math.round(size), size * 100 / totalSize, retSize.get(type));
        }
    }

}

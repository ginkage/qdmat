package com.google.ginkage.qdmat;

import org.eclipse.mat.SnapshotException;
import org.eclipse.mat.parser.internal.SnapshotFactory;
import org.eclipse.mat.parser.model.PrimitiveArrayImpl;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.snapshot.model.IClass;
import org.eclipse.mat.snapshot.model.IObject;
import org.eclipse.mat.snapshot.model.NamedReference;
import org.eclipse.mat.util.VoidProgressListener;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.*;

public class Analyzer {

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

                    IClass clazz = field.getClazz();
                    String type = clazz.getName();
                    if (type.equals("java.lang.ref.WeakReference") ||
                            type.equals("java.lang.ref.FinalizerReference") ||
                            type.equals("java.lang.reflect.ArtMethod") ||
                            type.contains("ClassLoader")) {
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
    private static void foldLeaves(Set<ObjectNode> graph, boolean soft, Map<ObjectNode, BufferedImage> bitmaps) {
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
            double denom = parents.size();

            // node doesn't have any outbound references, so transferring those is not required.
            for (ObjectNode parent : parents) {
                String name = parent.outRefs.get(node);
                parent.outRefs.remove(node);
                parent.size += node.size / denom;

                for (ObjectNode ret : node.retains.keySet()) {
                    parent.retain(ret, ObjectNode.combine(name, node.retains.get(ret)));
                }
                parent.retain(node, name);

                if (parent.getType().equals("android.graphics.Bitmap") && name.equals("mBuffer")) {
                    if (node.getType().equals("byte[]")) {
                        try {
                            Integer width = Integer.class.cast(parent.object.resolveValue("mWidth"));
                            Integer height = Integer.class.cast(parent.object.resolveValue("mHeight"));
                            PrimitiveArrayImpl array = PrimitiveArrayImpl.class.cast(node.object);
                            if (width != null && height != null && array != null) {
                                byte[] values = (byte[]) array.getValueArray();
                                int size = values.length / 4;
                                int[] argb = new int[size];
                                for (int i = 0, j = 0; i < size; ++i) {
                                    int r = (values[j++] & 0xFF);
                                    int g = (values[j++] & 0xFF);
                                    int b = (values[j++] & 0xFF);
                                    int a = (values[j++] & 0xFF);
                                    argb[i] = (a << 24) | (r << 16) | (g << 8) | b;
                                }
                                BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                                image.setRGB(0, 0, width, height, argb, 0, width);
                                bitmaps.put(parent, image);
                            }
                        } catch (SnapshotException e) {
                            e.printStackTrace();
                        }
                    }
                }

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
            String name = node.getType();
            for (ObjectNode ref : node.outRefs.keySet()) {
                String refName = ref.getType();
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
            String name = node.getType();
            for (ObjectNode ref : node.outRefs.keySet()) {
                String refName = ref.getType();
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
                String refName = ref.getType();
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

                String name = node.getType();
                String pname = parent.getType();

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

    public static void printSize(Set<ObjectNode> graph) {
        int totalSize = 0;
        Set<ObjectNode> retains = new HashSet<>();
        for (ObjectNode node : graph) {
            for (ObjectNode ret : node.retains.keySet()) {
                retains.add(ret);
            }
            totalSize += node.selfSize;
        }
        for (ObjectNode ret : retains) {
            totalSize += ret.selfSize;
        }
        System.out.println("size: " + totalSize);
    }

    public static SortedSet<ObjectNode> foldGraph(Set<ObjectNode> graph, Map<ObjectNode, BufferedImage> bitmaps) {
//        System.out.println("Nodes before reduction: " + graph.size());

        double totalSize = 0;
        final Map<String, Integer> typeCount = new HashMap<>();
        for (ObjectNode node : graph) {
            String name = node.getType();
            if (!typeCount.containsKey(name)) {
                typeCount.put(name, 1);
            } else {
                typeCount.put(name, typeCount.get(name) + 1);
            }
            totalSize += node.size;
        }
//        System.out.println("Total size: " + Math.round(totalSize));

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
            foldLeaves(graph, false, bitmaps);
            foldLinkedLists(graph);
            foldHelpers(graph, components);
        }

        // Second pass: soft-folding leaves.
        prevSize = -1;
        while (graph.size() != prevSize) {
            prevSize = graph.size();
            foldLeaves(graph, true, bitmaps);
            foldLinkedLists(graph);
            foldHelpers(graph, components);
        }

        SortedSet<ObjectNode> nodes = new TreeSet<>(new Comparator<ObjectNode>() {
            public int compare(ObjectNode a, ObjectNode b) {
                return (a.size < b.size ? 1 : -1);
            }
        });
        nodes.addAll(graph);

//        System.out.println("Nodes after reduction: " + graph.size());

        Map<ObjectNode, Integer> retCount = new HashMap<>();
        for (ObjectNode node : nodes) {
            for (ObjectNode ret : node.retains.keySet()) {
                if (retCount.containsKey(ret)) {
                    retCount.put(ret, retCount.get(ret) + 1);
                } else {
                    retCount.put(ret, 1);
                }
                ret.retainedBy.add(node);
                node.allSize += ret.selfSize;
            }
        }
/*
        for (ObjectNode bitmap : bitmaps.keySet()) {
            try {
                ImageIO.write(bitmaps.get(bitmap), "PNG",
                        new File(bitmap.object.getObjectId() + "-" + Math.round(bitmap.size) + ".png"));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
*/
        for (ObjectNode node : nodes) {
            for (ObjectNode ret : node.retains.keySet()) {
                if (retCount.get(ret) == 1) {
                    node.retSize += ret.retSize;
                    node.unique.add(ret);
                }
            }
        }

        return nodes;
    }

    public static void printStats(SortedSet<ObjectNode> nodes) {
        int totalSize = 0;
        for (ObjectNode node : nodes) {
            System.out.println(node.getType() +
                    ", weigthed_size=" + Math.round(node.size) +
                    ", inRefs=" + node.inRefs.size() + ", outRefs=" + node.outRefs.size() +
                    ", retain_size=" + node.retSize + " (" + node.unique.size() + " objects)");

            for (ObjectNode ret : node.retains.keySet()) {
                if (ret.getType().equals("android.graphics.Bitmap")) {
                    System.out.println("  Bitmap " + ret.object.getObjectId() + " (" + Math.round(ret.size) + " bytes):");
                    System.out.println("    " + node.retains.get(ret));
                }
            }

            // com.google.android.clockwork.home.cuecard.CueCardPageIndicator
            // com.google.android.clockwork.now.NowRowAdapter
            // com.android.clockwork.gestures.detector.MCAGestureClassifier
            // com.google.android.clockwork.mediacontrols.MediaControlReceiver
/*            if (node.retCount > 1000) {   //node.getType().equals("com.google.android.clockwork.stream.bridger.NotificationBridger")) {
                System.out.println("  Outbound references:");
                for (ObjectNode ref : node.outRefs.keySet()) {
                    System.out.println("    " + ref.getType() + " " + node.outRefs.get(ref));
                    System.out.println("      size=" + Math.round(ref.size) +
                            ", inRefs=" + ref.inRefs.size() + ", outRefs=" + ref.outRefs.size() +
                            ", retains=" + ref.retSize + " (" + ref.retCount + " objects)");
                }
                System.out.println("  Retained objects:");
                for (ObjectNode ret : node.retains.keySet()) {
                    System.out.println("    " + ret.getType() + ", size=" + ret.retSize + " :: " + node.retains.get(ret));
                    String name = ret.object.getClassSpecificName();
                    if (name != null) {
                        System.out.println("      data: \"" + name + "\"");
                    }
                }
            }

            for (ObjectNode ret : node.retains.keySet()) {
                if (ret.retSize > 4096 && retCount.get(ret) == 1) {
                    System.out.println("    " + ret.getType() + ", size=" + ret.retSize);
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
        for (ObjectNode node : nodes) {
            String name = node.getType();
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

    public static void printComponents(ComponentNode root, int level) {
        for (int i = 0; i < level; ++i) {
            System.out.print("  ");
        }
        System.out.println(root.name + ": " +
                root.children.size() + " children, " + root.objects.size() + " instances, " +
                root.retSize + " bytes unique in " + root.unique.size() + " objects of " +
                root.allSize + " bytes and " + root.retains.size() + " objects");
        for (ComponentNode child : root.children) {
            printComponents(child, level + 1);
        }
    }

    public static ComponentNode calculateComponents(Set<ObjectNode> graph) {
        Map<String, ComponentNode> components = new HashMap<>();
        ComponentNode root = new ComponentNode("");

        for (ObjectNode node : graph) {
            String name = node.getType();
            ComponentNode comp = components.get(name);
            if (comp == null) {
                comp = new ComponentNode(name);
                components.put(name, comp);
            }
            comp.addObject(node);

//            System.out.println("  Processing: " + name + " (" + node.retains.size() + " retains)");
            int lastDot = Math.max(name.lastIndexOf('$'), Math.max(name.lastIndexOf('.'), name.lastIndexOf('[')));
            while (lastDot >= 0) {
                String part = name.substring(0, lastDot);
                lastDot = Math.max(part.lastIndexOf('$'), Math.max(part.lastIndexOf('.'), part.lastIndexOf('[')));
                ComponentNode parent = components.get(part);
                if (parent == null) {
                    parent = new ComponentNode(part);
                    components.put(part, parent);
                }
//                System.out.println("+ " + part);
                parent.addChild(comp);
                comp = parent;
            }
//            System.out.println("+ *");
            root.addChild(comp);
        }

        root.recalcSize();

        return root;
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
        Map<ObjectNode, BufferedImage> bitmaps = new HashMap<>();
        SortedSet<ObjectNode> nodes = foldGraph(graph, bitmaps);
//        printStats(nodes);
        ComponentNode compRoot = calculateComponents(graph);
//        printComponents(compRoot, 0);

        HeapContents.run(graph, nodes, bitmaps, compRoot);
    }

}

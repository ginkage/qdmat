package com.google.ginkage.qdmat;

import org.eclipse.mat.snapshot.model.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class ObjectNode {

    public IObject object;
    public ObjectNode folder;
    public Map<ObjectNode, String> outRefs;
    public Set<ObjectNode> inRefs;
    public Map<ObjectNode, String> retains;
    public Set<ObjectNode> unique;
    public Set<ObjectNode> retainedBy;
    public double size;
    public int retSize;
    public int selfSize;
    public int allSize;

    // root node
    ObjectNode() {
        object = null;
        folder = null;
        outRefs = new HashMap<>();
        inRefs = null;
        size = 0;
        retSize = 0;
        selfSize = 0;
        allSize = 0;
        retains = null;
        unique = null;
        retainedBy = null;
    }

    // object node
    ObjectNode(IObject object, ObjectNode parent, String name) {
        this.object = object;
        this.folder = null;
        this.outRefs = new HashMap<>();
        this.inRefs = new HashSet<>();
        this.retains = new HashMap<>();
        this.unique = new HashSet<>();
        this.retainedBy = new HashSet<>();

        IClass clazz = object.getClazz();
        this.selfSize = clazz.getHeapSizePerInstance();
        if (clazz.isArrayType()) {
            int elementSize = ((object instanceof IObjectArray) ? 4 :
                    IPrimitiveArray.ELEMENT_SIZE[((IPrimitiveArray)object).getType()]);
            this.selfSize = elementSize * ((IArray)object).getLength();
        }
        this.retSize = this.selfSize;
        this.size = this.selfSize;
        this.allSize = this.selfSize;

        parent.link(this, name);
    }

    public void retain(ObjectNode ret, String name) {
        if (!retains.containsKey(ret)) {
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
        this.selfSize = a.selfSize;
        this.allSize = a.allSize;
        this.retains = new HashMap<>();
        this.unique = new HashSet<>();
        this.retainedBy = new HashSet<>();
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

    public String getType() {
        return object.getClazz().getName();
    }

    @Override
    public int hashCode() {
        return (object == null ? 0 : object.hashCode());
    }

}

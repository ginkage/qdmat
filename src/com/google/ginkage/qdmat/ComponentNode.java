package com.google.ginkage.qdmat;

import java.util.HashSet;
import java.util.Set;

public class ComponentNode {
    public Set<ObjectNode> retains;
    public Set<ObjectNode> unique;
    public Set<ObjectNode> objects;
    public int retSize;
    public int allSize;
    public String name;
    public Set<ComponentNode> children;

    public ComponentNode(String name) {
        this.name = name;
        this.objects = new HashSet<>();
        this.retains = new HashSet<>();
        this.unique = new HashSet<>();
        this.children = new HashSet<>();
        this.retSize = 0;
    }

    public void addObject(ObjectNode node) {
        objects.add(node);
        for (ObjectNode ret : node.retains.keySet()) {
            retains.add(ret);
        }
    }

    public void recalcSize() {
        allSize = 0;
        unique = new HashSet<>();
        for (ObjectNode ret : retains) {
            allSize += ret.selfSize;

            boolean isUnique = true;
            for (ObjectNode ref : ret.retainedBy) {
                if (!ref.getType().startsWith(name)) {
                    isUnique = false;
                    break;
                }
            }

            if (isUnique) {
                unique.add(ret);
            }
        }

        retSize = 0;
        for (ObjectNode ret : unique) {
            retSize += ret.selfSize;
        }

        for (ComponentNode child : children) {
            child.recalcSize();
        }
    }

    public void addChild(ComponentNode node) {
        children.add(node);
        for (ObjectNode ret : node.retains) {
            retains.add(ret);
        }
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

}

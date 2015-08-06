package com.google.ginkage.qdmat;

import java.util.HashSet;
import java.util.Set;

public class ComponentNode {
    public Set<ObjectNode> retains;
    public Set<ObjectNode> unique;
    public Set<ObjectNode> objects;
    public double softSize;
    public int retSize;
    public int allSize;
    public int selfSize;
    public String name;
    public Set<ComponentNode> children;
    public boolean isClass;

    public ComponentNode(String name) {
        this.name = name;
        this.objects = new HashSet<>();
        this.retains = new HashSet<>();
        this.unique = new HashSet<>();
        this.children = new HashSet<>();
        this.retSize = 0;
        this.allSize = 0;
        this.softSize = 0;
        this.selfSize = 0;
        this.isClass = false;
    }

    public void addObject(ObjectNode node) {
        objects.add(node);
        for (ObjectNode ret : node.retains.keySet()) {
            retains.add(ret);
        }
        softSize += node.size;
        selfSize += node.selfSize;
        this.isClass = true;
    }

    public double recalcSize() {
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

        for (ObjectNode ret : unique) {
            retSize += ret.selfSize;
        }

        for (ComponentNode child : children) {
            softSize += child.recalcSize();
            selfSize += child.selfSize;
        }

        retSize += selfSize;
        allSize += selfSize;

        return softSize;
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

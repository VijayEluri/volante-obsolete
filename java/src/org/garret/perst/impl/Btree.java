package org.garret.perst.impl;
import  org.garret.perst.*;
import  java.util.ArrayList;
import  java.util.Iterator;
import  java.util.NoSuchElementException;

class Btree extends PersistentResource implements Index { 
    int       root;
    int       height;
    int       type;
    int       nElems;
    boolean   unique;

    Btree() {}

    Btree(Class cls, boolean unique) {
        type = ClassDescriptor.getTypeCode(cls);
        if (type >= ClassDescriptor.tpLink) { 
            throw new StorageError(StorageError.UNSUPPORTED_INDEX_TYPE, cls);
        }
        this.unique = unique;
    }

    Btree(byte[] obj, int offs) {
        root = Bytes.unpack4(obj, offs);
        offs += 4;
        height = Bytes.unpack4(obj, offs);
        offs += 4;
        type = Bytes.unpack4(obj, offs);
        offs += 4;
        nElems = Bytes.unpack4(obj, offs);
        offs += 4;
        unique = obj[offs] != 0;
    }

    static final int op_done      = 0;
    static final int op_overflow  = 1;
    static final int op_underflow = 2;
    static final int op_not_found = 3;
    static final int op_duplicate = 4;
    static final int op_overwrite = 5;

    public IPersistent get(Key key) { 
        if (key.type != type) { 
            throw new StorageError(StorageError.INCOMPATIBLE_KEY_TYPE);
        }
        if (root != 0) { 
            ArrayList list = new ArrayList();
            BtreePage.find((StorageImpl)getStorage(), root, key, key, type, height, list);
            if (list.size() > 1) { 
                throw new StorageError(StorageError.KEY_NOT_UNIQUE);
            } else if (list.size() == 0) { 
                return null;
            } else { 
                return (IPersistent)list.get(0);
            }
        }
        return null;
    }

    static final IPersistent[] emptySelection = new IPersistent[0];

    public IPersistent[] get(Key from, Key till) {
        if ((from != null && from.type != type) || (till != null && till.type != type)) { 
            throw new StorageError(StorageError.INCOMPATIBLE_KEY_TYPE);
        }
        if (root != 0) { 
            ArrayList list = new ArrayList();
            BtreePage.find((StorageImpl)getStorage(), root, from, till, type, height, list);
            if (list.size() == 0) { 
                return emptySelection;
            } else { 
                return (IPersistent[])list.toArray(new IPersistent[list.size()]);
            }
        }
        return emptySelection;
    }

    public boolean put(Key key, IPersistent obj) {
        return insert(key, obj, false);
    }

    public void set(Key key, IPersistent obj) {
        insert(key, obj, true);
    }

    final boolean insert(Key key, IPersistent obj, boolean overwrite) {
        StorageImpl db = (StorageImpl)getStorage();
        if (key.type != type) { 
            throw new StorageError(StorageError.INCOMPATIBLE_KEY_TYPE);
        }
        if (!obj.isPersistent()) { 
            db.storeObject(obj);
        }
        BtreeKey ins = new BtreeKey(key, obj.getOid());
        if (root == 0) { 
	    root = BtreePage.allocate(db, 0, type, ins);
	    height = 1;
        } else { 
            int result = BtreePage.insert(db, root, type, ins, height, unique, overwrite);
	    if (result == op_overflow) { 
		root = BtreePage.allocate(db, root, type, ins);
		height += 1;
	    } else if (result == op_duplicate) { 
                return false;
	    } else if (result == op_overwrite) { 
                return true;
            }
        }
        nElems += 1;
        store();
        return true;
    }

    public void  remove(Key key, IPersistent obj) {
        remove(new BtreeKey(key, obj.getOid()));
    }

    
    void remove(BtreeKey rem) {
        StorageImpl db = (StorageImpl)getStorage();
        if (rem.key.type != type) { 
            throw new StorageError(StorageError.INCOMPATIBLE_KEY_TYPE);
        }
        if (root == 0) {
            throw new StorageError(StorageError.KEY_NOT_FOUND);
        }
	int result = BtreePage.remove(db, root, type, rem, height);
        if (result == op_not_found) { 
	    throw new StorageError(StorageError.KEY_NOT_FOUND);
        }
        nElems -= 1;
	if (result == op_underflow && height != 1) { 
	    Page pg = db.getPage(root);
	    if (BtreePage.getnItems(pg) == 0) { 			
		int newRoot = (type == ClassDescriptor.tpString) 
                    ? BtreePage.getKeyStrOid(pg, 0)
                    : BtreePage.getReference(pg, BtreePage.maxItems-1);
		db.freePage(root);
                root = newRoot;
		height -= 1;
	    }
	    db.pool.unfix(pg);
	} else if (result == op_overflow) { 
	    root = BtreePage.allocate(db, root, type, rem);
	    height += 1;
	}
        store();
    }
        
    public void remove(Key key) {
        if (!unique) { 
	    throw new StorageError(StorageError.KEY_NOT_UNIQUE);
        }
        remove(new BtreeKey(key, 0));
    }
        
        
    public int size() {
        return nElems;
    }
    
    public void clear() {
        if (root != 0) { 
            BtreePage.purge((StorageImpl)getStorage(), root, type, height);
            root = 0;
            nElems = 0;
            height = 0;
            store();
        }
    }
        
    public IPersistent[] toArray() {
        IPersistent[] arr = new IPersistent[nElems];
        if (root != 0) { 
            BtreePage.traverseForward((StorageImpl)getStorage(), root, type, height, arr, 0);
        }
        return arr;
    }

    public void deallocate() { 
        if (root != 0) { 
            BtreePage.purge((StorageImpl)getStorage(), root, type, height);
        }
        super.deallocate();
    }

    public void markTree() { 
        if (root != 0) { 
            BtreePage.markPage((StorageImpl)getStorage(), root, type, height);
        }
    }        

    static class BtreeIterator implements Iterator { 
        BtreeIterator(StorageImpl db, int pageId, int height) { 
            this.db = db;
            pageStack = new int[height];
            posStack =  new int[height];
            sp = 0;
            while (--height >= 0) { 
                posStack[sp] = 0;
                pageStack[sp] = pageId;
                Page pg = db.getPage(pageId);
                pageId = getReference(pg, 0);
                end = BtreePage.getnItems(pg);
                db.pool.unfix(pg);
                sp += 1;
            }
        }

        protected int getReference(Page pg, int pos) { 
            return BtreePage.getReference(pg, BtreePage.maxItems-1-pos);
        }

        public boolean hasNext() {
            return sp > 0 && posStack[sp-1] < end;
        }

        public Object next() {
            if (sp == 0 || posStack[sp-1] >= end) { 
                throw new NoSuchElementException();
            }
            int pos = posStack[sp-1];   
            Page pg = db.getPage(pageStack[sp-1]);
            int oid = getReference(pg, pos);        
            Object obj = db.lookupObject(oid, null);
            if (++pos == end) { 
                while (--sp != 0) { 
                    db.pool.unfix(pg);
                    pos = posStack[sp-1];
                    pg = db.getPage(pageStack[sp-1]);
                    if (++pos <= BtreePage.getnItems(pg)) {
                        posStack[sp-1] = pos;
                        do { 
                            int pageId = getReference(pg, pos);
                            db.pool.unfix(pg);
                            pg = db.getPage(pageId);
                            end = BtreePage.getnItems(pg);
                            pageStack[sp] = pageId;
                            posStack[sp] = pos = 0;
                        } while (++sp < pageStack.length);
                        break;
                    }
                }
            } else {
                posStack[sp-1] = pos;
            }
            db.pool.unfix(pg);
            return obj;
        }

        public void remove() { 
            throw new UnsupportedOperationException();
        }

        StorageImpl db;
        int[]       pageStack;
        int[]       posStack;
        int         sp;
        int         end;
    }

    static class BtreeStrIterator extends BtreeIterator { 
        BtreeStrIterator(StorageImpl db, int pageId, int height) { 
            super(db, pageId, height);
        }

        protected int getReference(Page pg, int pos) { 
            return BtreePage.getKeyStrOid(pg, pos);
        }
    }

    public Iterator iterator() { 
        return type == ClassDescriptor.tpString 
            ? new BtreeStrIterator((StorageImpl)getStorage(), root, height)
            : new BtreeIterator((StorageImpl)getStorage(), root, height);
    }
}


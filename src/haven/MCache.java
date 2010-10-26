/*
 *  This file is part of the Haven & Hearth game client.
 *  Copyright (C) 2009 Fredrik Tolf <fredrik@dolda2000.com>, and
 *                     Björn Johannessen <johannessen.bjorn@gmail.com>
 *
 *  Redistribution and/or modification of this file is subject to the
 *  terms of the GNU Lesser General Public License, version 3, as
 *  published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  Other parts of this source tree adhere to other copying
 *  rights. Please see the file `COPYING' in the root directory of the
 *  source tree for details.
 *
 *  A copy the GNU Lesser General Public License is distributed along
 *  with the source tree of which this file is a part in the file
 *  `doc/LPGL-3'. If it is missing for any reason, please see the Free
 *  Software Foundation's website at <http://www.fsf.org/>, or write
 *  to the Free Software Foundation, Inc., 59 Temple Place, Suite 330,
 *  Boston, MA 02111-1307 USA
 */

package haven;

import java.util.*;
import haven.Resource.Tileset;
import haven.Resource.Tile;
import java.util.zip.Inflater;

public class MCache {
    public static final Coord tilesz = new Coord(11, 11);
    public static final Coord cmaps = new Coord(100, 100);
    private final Resource[] sets = new Resource[256];
    private final Tileset[] csets = new Tileset[256];
    Map<Coord, Request> req = new HashMap<Coord, Request>();
    Map<Coord, Grid> grids = new HashMap<Coord, Grid>();
    Session sess;
    Set<Overlay> ols = new HashSet<Overlay>();
    Random gen = new Random();
    Map<Integer, Defrag> fragbufs = new TreeMap<Integer, Defrag>();

    public static class LoadingMap extends Throwable {
	private LoadingMap() {}
    }

    private static class Request {
	private long lastreq = 0;
	private int reqs = 0;
    }

    public class Overlay {
	Coord c1, c2;
	int mask;

	public Overlay(Coord c1, Coord c2, int mask) {
	    this.c1 = c1;
	    this.c2 = c2;
	    this.mask = mask;
	    ols.add(this);
	}

	public void destroy() {
	    ols.remove(this);
	}

	public void update(Coord c1, Coord c2) {
	    this.c1 = c1;
	    this.c2 = c2;
	}
    }

    public class Grid {
	public final int tiles[] = new int[cmaps.x * cmaps.y];
	public final int z[] = new int[cmaps.x * cmaps.y];
	public final int ol[] = new int[cmaps.x * cmaps.y];
	private Collection<Gob> fo;
	public final Coord gc, ul;
	public final long id;
	String mnm;

	private class Flavobj extends Gob {
	    private Flavobj(Coord c) {
		super(sess.glob, c);
	    }

	    public Random mkrandoom() {
		Random r = new Random(Grid.this.id);
		r.setSeed(r.nextInt() ^ rc.x);
		r.setSeed(r.nextInt() ^ rc.y);
		return(r);
	    }
	}

	public Grid(Coord gc) {
	    this.gc = gc;
	    this.ul = gc.mul(cmaps);
	    id = (((long)gc.x) << 32l) ^ ((long)gc.y);
	}

	public int gettile(Coord tc) {
	    return(tiles[tc.x + (tc.y * cmaps.x)]);
	}

	public int getz(Coord tc) {
	    return(z[tc.x + (tc.y * cmaps.x)]);
	}

	public int getol(Coord tc) {
	    return(ol[tc.x + (tc.y * cmaps.x)]);
	}

	private void makeflavor() throws LoadingMap {
	    Collection<Gob> fo = new LinkedList<Gob>();
	    fo.clear();
	    Coord c = new Coord(0, 0);
	    Coord tc = gc.mul(cmaps);
	    int i = 0;
	    Random rnd = new Random(id);
	    for(c.y = 0; c.y < cmaps.x; c.y++) {
		for(c.x = 0; c.x < cmaps.y; c.x++, i++) {
		    Tileset set = tileset(tiles[i]);
		    if(set.flavobjs.size() > 0) {
			if(rnd.nextInt(set.flavprob) == 0) {
			    Resource r = set.flavobjs.pick(rnd);
			    Gob g = new Flavobj(c.add(tc).mul(tilesz)); 
			    g.setattr(new ResDrawable(g, r));
			    fo.add(g);
			}
		    }
		}
	    }
	    this.fo = fo;
	}

	public Collection<Gob> getfo() throws LoadingMap {
	    if(fo == null)
		makeflavor();
	    return(fo);
	}
    }

    public MCache(Session sess) {
	this.sess = sess;
    }

    public void invalidate(Coord cc) {
	synchronized(req) {
	    if(req.get(cc) == null)
		req.put(cc, new Request());
	}
    }

    public void invalblob(Message msg) {
	int type = msg.uint8();
	if(type == 0) {
	    invalidate(msg.coord());
	} else if(type == 1) {
	    Coord ul = msg.coord();
	    Coord lr = msg.coord();
	    trim(ul, lr);
	} else if(type == 2) {
	    trimall();
	}
    }

    private Grid cached = null;
    public Grid getgrid(Coord gc) throws LoadingMap {
	synchronized(grids) {
	    if((cached == null) || !cached.gc.equals(cached)) {
		cached = grids.get(gc);
		if(cached == null) {
		    request(gc);
		    throw(new LoadingMap());
		}
	    }
	    return(cached);
	}
    }

    public Grid getgridt(Coord tc) throws LoadingMap {
	return(getgrid(tc.div(cmaps)));
    }

    public int gettile(Coord tc) throws LoadingMap {
	Grid g = getgridt(tc);
	return(g.gettile(tc.sub(g.ul)));
    }

    public int getz(Coord tc) throws LoadingMap {
	Grid g = getgridt(tc);
	return(g.getz(tc.sub(g.ul)));
    }

    public int getol(Coord tc) throws LoadingMap {
	Grid g = getgridt(tc);
	int ol = g.getol(tc.sub(g.ul));
	for(Overlay lol : ols) {
	    if(tc.isect(lol.c1, lol.c2.add(lol.c1.inv()).add(new Coord(1, 1))))
		ol |= lol.mask;
	}
	return(ol);
    }

    public void mapdata2(Message msg) {
	Coord c = msg.coord();
	String mmname = msg.string().intern();
	if(mmname.equals(""))
	    mmname = null;
	int[] pfl = new int[256];
	while(true) {
	    int pidx = msg.uint8();
	    if(pidx == 255)
		break;
	    pfl[pidx] = msg.uint8();
	}
	Message blob = new Message(0);
	{
	    Inflater z = new Inflater();
	    z.setInput(msg.blob, msg.off, msg.blob.length - msg.off);
	    byte[] buf = new byte[10000];
	    while(true) {
		try {
		    int len;
		    if((len = z.inflate(buf)) == 0) {
			if(!z.finished())
			    throw(new RuntimeException("Got unterminated map blob"));
			break;
		    }
		    blob.addbytes(buf, 0, len);
		} catch(java.util.zip.DataFormatException e) {
		    throw(new RuntimeException("Got malformed map blob", e));
		}
	    }
	}
	synchronized(grids) {
	    synchronized(req) {
		if(req.containsKey(c)) {
		    Grid g = new Grid(c);
		    g.mnm = mmname;
		    for(int i = 0; i < g.tiles.length; i++)
			g.tiles[i] = blob.uint8();
		    for(int i = 0; i < g.ol.length; i++)
			g.ol[i] = 0;
		    while(true) {
			int pidx = blob.uint8();
			if(pidx == 255)
			    break;
			int fl = pfl[pidx];
			int type = blob.uint8();
			Coord c1 = new Coord(blob.uint8(), blob.uint8());
			Coord c2 = new Coord(blob.uint8(), blob.uint8());
			int ol;
			if(type == 0) {
			    if((fl & 1) == 1)
				ol = 2;
			    else
				ol = 1;
			} else if(type == 1) {
			    if((fl & 1) == 1)
				ol = 8;
			    else
				ol = 4;
			} else {
			    throw(new RuntimeException("Unknown plot type " + type));
			}
			for(int y = c1.y; y <= c2.y; y++) {
			    for(int x = c1.x; x <= c2.x; x++) {
				g.ol[x + (y * cmaps.x)] |= ol;
			    }
			}
		    }
		    req.remove(c);
		    if(grids.remove(c) == cached)
			cached = null;
		    grids.put(c, g);
		}
	    }
	}
    }

    public void mapdata(Message msg) {
	long now = System.currentTimeMillis();
	int pktid = msg.int32();
	int off = msg.uint16();
	int len = msg.uint16();
	Defrag fragbuf;
	synchronized(fragbufs) {
	    if((fragbuf = fragbufs.get(pktid)) == null) {
		fragbuf = new Defrag(len);
		fragbufs.put(pktid, fragbuf);
	    }
	    fragbuf.add(msg.blob, 8, msg.blob.length - 8, off);
	    fragbuf.last = now;
	    if(fragbuf.done()) {
		mapdata2(fragbuf.msg());
		fragbufs.remove(pktid);
	    }
	
	    /* Clean up old buffers */
	    for(Iterator<Map.Entry<Integer, Defrag>> i = fragbufs.entrySet().iterator(); i.hasNext();) {
		Map.Entry<Integer, Defrag> e = i.next();
		Defrag old = e.getValue();
		if(now - old.last > 10000)
		    i.remove();
	    }
	}
    }

    public Tileset tileset(int i) throws LoadingMap {
	if(csets[i] == null) {
	    if(sets[i].loading)
		throw(new LoadingMap());
	    csets[i] = sets[i].layer(Resource.tileset);
	}
	return(csets[i]);
    }

    public void tilemap(Message msg) {
	while(!msg.eom()) {
	    int id = msg.uint8();
	    String resnm = msg.string();
	    int resver = msg.uint16();
	    sets[id] = Resource.load(resnm, resver);
	}
    }

    public void trimall() {
	synchronized(grids) {
	    synchronized(req) {
		grids.clear();
		req.clear();
	    }
	}
    }

    public void trim(Coord ul, Coord lr) {
	synchronized(grids) {
	    synchronized(req) {
		for(Iterator<Map.Entry<Coord, Grid>> i = grids.entrySet().iterator(); i.hasNext();) {
		    Map.Entry<Coord, Grid> e = i.next();
		    Coord gc = e.getKey();
		    Grid g = e.getValue();
		    if((gc.x < ul.x) || (gc.y < ul.y) || (gc.x > lr.x) || (gc.y > lr.y))
			i.remove();
		}
		for(Iterator<Coord> i = req.keySet().iterator(); i.hasNext();) {
		    Coord gc = i.next();
		    if((gc.x < ul.x) || (gc.y < ul.y) || (gc.x > lr.x) || (gc.y > lr.y))
			i.remove();
		}
	    }
	}
    }

    public void request(Coord gc) {
	synchronized(req) {
	    if(!req.containsKey(gc))
		req.put(gc, new Request());
	}
    }

    public void sendreqs() {
	long now = System.currentTimeMillis();
	synchronized(req) {
	    for(Iterator<Map.Entry<Coord, Request>> i = req.entrySet().iterator(); i.hasNext();) {
		Map.Entry<Coord, Request> e = i.next();
		Coord c = e.getKey();
		Request r = e.getValue();
		if(now - r.lastreq > 1000) {
		    r.lastreq = now;
		    if(++r.reqs >= 5) {
			i.remove();
		    } else {
			Message msg = new Message(Session.MSG_MAPREQ);
			msg.addcoord(c);
			sess.sendmsg(msg);
		    }
		}
	    }
	}
    }
}

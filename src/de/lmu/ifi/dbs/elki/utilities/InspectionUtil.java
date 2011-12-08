package de.lmu.ifi.dbs.elki.utilities;

/*
 This file is part of ELKI:
 Environment for Developing KDD-Applications Supported by Index-Structures

 Copyright (C) 2011
 Ludwig-Maximilians-Universität München
 Lehr- und Forschungseinheit für Datenbanksysteme
 ELKI Development Team

 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU Affero General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU Affero General Public License for more details.

 You should have received a copy of the GNU Affero General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Stack;
import java.util.Vector;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import de.lmu.ifi.dbs.elki.logging.LoggingUtil;
import de.lmu.ifi.dbs.elki.utilities.optionhandling.parameters.ClassParameter;

/**
 * A collection of inspection-related utility functions.
 * 
 * @author Erich Schubert
 * 
 * @apiviz.uses InspectionUtilFrequentlyScanned
 */
public class InspectionUtil {
  /**
   * Default package ignores.
   */
  private static final String[] DEFAULT_IGNORES = {
      // Sun Java
  "java.", "com.sun.",
      // Batik classes
  "org.apache.",
      // W3C / SVG / XML classes
  "org.w3c.", "org.xml.", "javax.xml.",
      // JUnit
  "org.junit.", "junit.", "org.hamcrest.",
      // Eclipse
  "org.eclipse.",
      // ApiViz
  "org.jboss.apiviz.",
      // JabRef
  "spin.", "osxadapter.", "antlr.", "ca.odell.", "com.jgoodies.", "com.michaelbaranov.", "com.mysql.", "gnu.dtools.", "net.sf.ext.", "net.sf.jabref.", "org.antlr.", "org.gjt.", "org.java.plugin.", "org.jempbox.", "org.pdfbox.", "wsi.ra.",
      // GNU trove
  "gnu.trove.",
  //
  };

  /**
   * If we have a non-static classpath, we do more extensive scanning for user
   * extensions.
   */
  public static final boolean NONSTATIC_CLASSPATH;

  // Check for non-jar entries in classpath.
  static {
    String[] classpath = System.getProperty("java.class.path").split(System.getProperty("path.separator"));
    boolean hasnonstatic = false;
    for(String path : classpath) {
      if(!path.endsWith(".jar")) {
        hasnonstatic = true;
      }
    }
    NONSTATIC_CLASSPATH = hasnonstatic;
  }

  private static WeakReference<List<Class<?>>> CLASS_CACHE = new WeakReference<List<Class<?>>>(null);

  /**
   * Cached version of "findAllImplementations". For Parameterizable classes
   * only!
   * 
   * @param c Class to scan for
   * @return Found implementations
   */
  public static List<Class<?>> cachedFindAllImplementations(Class<?> c) {
    if(c == null) {
      return Collections.emptyList();
    }
    if(InspectionUtilFrequentlyScanned.class.isAssignableFrom(c)) {
      List<Class<?>> cache = CLASS_CACHE.get();
      if(cache == null) {
        cache = findAllImplementations(InspectionUtilFrequentlyScanned.class, false);
        CLASS_CACHE = new WeakReference<List<Class<?>>>(cache);
      }
      ArrayList<Class<?>> list = new ArrayList<Class<?>>();
      for(Class<?> cls : cache) {
        if(c.isAssignableFrom(cls)) {
          list.add(cls);
        }
      }
      return list;
    }
    else {
      // Need to scan - not cached.
      // LoggingUtil.logExpensive(Level.FINE,
      // "Slow scan for implementations: "+c.getName());
      return findAllImplementations(c, false);
    }
  }

  /**
   * Find all implementations of a given class in the classpath.
   * 
   * Note: returned classes may be abstract.
   * 
   * @param c Class restriction
   * @param everything include interfaces, abstract and private classes
   * @return List of found classes.
   */
  public static List<Class<?>> findAllImplementations(Class<?> c, boolean everything) {
    String[] classpath = System.getProperty("java.class.path").split(System.getProperty("path.separator"));
    return findAllImplementations(classpath, c, DEFAULT_IGNORES, everything);
  }

  /**
   * Find all implementations of a given class.
   * 
   * @param classpath Classpath to use (JARs and folders supported)
   * @param c Class restriction
   * @param ignorepackages List of packages to ignore
   * @param everything include interfaces, abstract and private classes
   * @return List of found classes.
   */
  public static List<Class<?>> findAllImplementations(String[] classpath, Class<?> c, String[] ignorepackages, boolean everything) {
    // Collect iterators
    Vector<Iterable<String>> iters = new Vector<Iterable<String>>(classpath.length);
    for(String path : classpath) {
      File p = new File(path);
      if(path.endsWith(".jar")) {
        iters.add(new JarClassIterator(path));
      }
      else if(p.isDirectory()) {
        iters.add(new DirClassIterator(p));
      }
    }

    ArrayList<Class<?>> res = new ArrayList<Class<?>>();
    ClassLoader cl = ClassLoader.getSystemClassLoader();
    for(Iterable<String> iter : iters) {
      for(String classname : iter) {
        boolean ignore = false;
        for(String pkg : ignorepackages) {
          if(classname.startsWith(pkg)) {
            ignore = true;
            break;
          }
        }
        if(ignore) {
          continue;
        }
        try {
          Class<?> cls = cl.loadClass(classname);
          // skip abstract / private classes.
          if(!everything && (Modifier.isInterface(cls.getModifiers()) || Modifier.isAbstract(cls.getModifiers()) || Modifier.isPrivate(cls.getModifiers()))) {
            continue;
          }
          // skip classes where we can't get a full name.
          if(cls.getCanonicalName() == null) {
            continue;
          }
          if(c.isAssignableFrom(cls)) {
            res.add(cls);
          }
        }
        catch(ClassNotFoundException e) {
          continue;
        }
        catch(NoClassDefFoundError e) {
          continue;
        }
        catch(Exception e) {
          continue;
        }
      }
    }
    Collections.sort(res, new ClassSorter());
    return res;
  }

  /**
   * Class to iterate over a Jar file.
   * 
   * @author Erich Schubert
   * 
   * @apiviz.exclude
   */
  static class JarClassIterator implements Iterator<String>, Iterable<String> {
    private Enumeration<JarEntry> jarentries;

    private String ne;

    /**
     * Constructor from Jar file.
     * 
     * @param path Jar file entries to iterate over.
     */
    public JarClassIterator(String path) {
      try {
        JarFile jf = new JarFile(path);
        this.jarentries = jf.entries();
        this.ne = findNext();
      }
      catch(IOException e) {
        LoggingUtil.exception("Error opening jar file: " + path, e);
        this.jarentries = null;
        this.ne = null;
      }
    }

    @Override
    public boolean hasNext() {
      // Do we have a next entry?
      return (ne != null);
    }

    /**
     * Find the next entry, since we need to skip some jar file entries.
     * 
     * @return next entry or null
     */
    private String findNext() {
      while(jarentries.hasMoreElements()) {
        JarEntry je = jarentries.nextElement();
        String name = je.getName();
        if(name.endsWith(".class")) {
          String classname = name.substring(0, name.length() - ".class".length());
          if(classname.endsWith(ClassParameter.FACTORY_POSTFIX) || !classname.contains("$")) {
            return classname.replace('/', '.');
          }
        }
      }
      return null;
    }

    @Override
    public String next() {
      // Return the previously stored entry.
      String ret = ne;
      ne = findNext();
      return ret;
    }

    @Override
    public void remove() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Iterator<String> iterator() {
      return this;
    }
  }

  /**
   * Class to iterate over a directory tree.
   * 
   * @author Erich Schubert
   * 
   * @apiviz.exclude
   */
  static class DirClassIterator implements Iterator<String>, Iterable<String> {
    private String prefix;

    private Stack<File> set = new Stack<File>();

    private String cur;

    /**
     * Constructor from Directory
     * 
     * @param path Directory to iterate over
     */
    public DirClassIterator(File path) {
      this.prefix = path.getAbsolutePath();
      if(prefix.charAt(prefix.length() - 1) != File.separatorChar) {
        prefix = prefix + File.separatorChar;
      }

      this.set.push(path);
      this.cur = findNext();
    }

    @Override
    public boolean hasNext() {
      // Do we have a next entry?
      return (cur != null);
    }

    /**
     * Find the next entry, since we need to skip some jar file entries.
     * 
     * @return next entry or null
     */
    private String findNext() {
      while(set.size() > 0) {
        File f = set.pop();
        // Classes
        if(f.getName().endsWith(".class")) {
          String name = f.getAbsolutePath();
          if(name.startsWith(prefix)) {
            name = name.substring(prefix.length());
          }
          else {
            LoggingUtil.warning("I was expecting all directories to start with '" + prefix + "' but '" + name + "' did not.");
          }
          String classname = name.substring(0, name.length() - ".class".length());
          if(classname.endsWith(ClassParameter.FACTORY_POSTFIX) || !classname.contains("$")) {
            return classname.replace(File.separatorChar, '.');
          }
          continue;
        }
        // recurse into directories
        if(f.isDirectory()) {
          for(File newf : f.listFiles()) {
            // TODO: do not recurse into ignored packages!.
            // Ignore unix-hidden files/dirs
            if(!newf.getName().startsWith(".")) {
              set.push(newf);
            }
          }
          continue;
        }
      }
      return null;
    }

    @Override
    public String next() {
      // Return the previously stored entry.
      String ret = this.cur;
      this.cur = findNext();
      return ret;
    }

    @Override
    public void remove() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Iterator<String> iterator() {
      return this;
    }
  }

  /**
   * Sort classes by their class name. Package first, then class.
   * 
   * @author Erich Schubert
   * 
   * @apiviz.exclude
   */
  public static class ClassSorter implements Comparator<Class<?>> {
    @Override
    public int compare(Class<?> o1, Class<?> o2) {
      int pkgcmp = o1.getPackage().getName().compareTo(o2.getPackage().getName());
      if(pkgcmp != 0) {
        return pkgcmp;
      }
      return o1.getCanonicalName().compareTo(o2.getCanonicalName());
    }
  }
}
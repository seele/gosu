/*
 * Copyright 2012. Guidewire Software, Inc.
 */

package gw.internal.gosu.parser;

import gw.config.CommonServices;
import gw.fs.IDirectory;
import gw.fs.IResource;
import gw.lang.cli.SystemExitIgnoredException;
import gw.lang.gosuc.GosucProject;
import gw.internal.gosu.module.DefaultSingleModule;
import gw.internal.gosu.module.JreModule;
import gw.internal.gosu.module.Module;
import gw.lang.init.GosuPathEntry;
import gw.lang.parser.GosuParserFactory;
import gw.lang.parser.IGosuParser;
import gw.lang.parser.IGosuProgramParser;
import gw.lang.parser.IParseResult;
import gw.lang.parser.ParserOptions;
import gw.lang.reflect.ITypeLoader;
import gw.lang.reflect.TypeSystem;
import gw.lang.reflect.gs.BytecodeOptions;
import gw.lang.reflect.java.JavaTypes;
import gw.lang.reflect.module.Dependency;
import gw.lang.reflect.module.IExecutionEnvironment;
import gw.lang.reflect.module.IModule;
import gw.lang.reflect.module.IProject;

import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public class ExecutionEnvironment implements IExecutionEnvironment
{
  static {
    ContextSensitiveCodeRunner.ensureLoadedForDebuggerEval();
  }
  private static final IProject DEFAULT_PROJECT = new DefaultSingleModuleRuntimeProject();
  private static final Map<Object, ExecutionEnvironment> INSTANCES = new WeakHashMap<Object, ExecutionEnvironment>();
  private static ExecutionEnvironment THE_ONE;
  public static final String CLASS_REDEFINER_THREAD = "Gosu class redefiner";

  private IProject _project;
  private List<IModule> _modules;
  private IModule _defaultModule;
  private IModule _jreModule;
  private IModule _rootModule;
  private TypeSystemState _state = TypeSystemState.STOPPED;

  public static ExecutionEnvironment instance()
  {
    if( INSTANCES.size() == 1 )
    {
      return THE_ONE == null
             ? THE_ONE = INSTANCES.values().iterator().next()
             : THE_ONE;
    }

    IModule mod = INSTANCES.size() > 0 ? TypeSystem.getCurrentModule() : null;
    if( mod != null )
    {
      ExecutionEnvironment execEnv = (ExecutionEnvironment)mod.getExecutionEnvironment();
      if( execEnv == null )
      {
        throw new IllegalStateException( "Module, " + mod.getName() + ", has a null execution environment. This is bad." );
      }
      return execEnv;
    }

    if( INSTANCES.size() > 0 )
    {
      // Return first non-default project
      // Yes, this is a guess, but we need to guess for the case where we're running tests
      // and loading classes in lots of threads where the current module is not pushed
      for( ExecutionEnvironment execEnv: INSTANCES.values() )
      {
        if( execEnv.getProject() != DEFAULT_PROJECT &&
            !execEnv.getProject().isDisposed() )
        {
          return execEnv;
        }
      }
    }

    return instance( DEFAULT_PROJECT );
  }
  public static ExecutionEnvironment instance( IProject project )
  {
    if( project == null )
    {
      throw new IllegalStateException( "Project must not be null" );
    }

    if( project instanceof IExecutionEnvironment )
    {
      throw new RuntimeException( "Passed in ExecutionEnvironment as project" );
    }

    ExecutionEnvironment execEnv = INSTANCES.get( project );
    if( execEnv == null )
    {
      INSTANCES.put( project, execEnv = new ExecutionEnvironment( project ) );
    }

    return execEnv;
  }

  public static Collection<? extends IExecutionEnvironment> getAll()
  {
    return INSTANCES.values();
  }

  private ExecutionEnvironment( IProject project )
  {
    _project = project;
    _modules = new ArrayList<IModule>();
  }

  public IProject getProject()
  {
    return _project;
  }

  public List<? extends IModule> getModules() {
    return _modules;
  }

  public void initializeDefaultSingleModule( List<? extends GosuPathEntry> pathEntries ) {
    _state = TypeSystemState.STARTING;
    try {
      DefaultSingleModule singleModule = _defaultModule == null ? new DefaultSingleModule( this ) : (DefaultSingleModule)_defaultModule;
      _defaultModule = singleModule;
      _modules = new ArrayList<IModule>(Collections.singletonList(singleModule));

      List<IDirectory> allSources = new ArrayList<IDirectory>();
      for( GosuPathEntry pathEntry : pathEntries )
      {
        singleModule.addRoot( pathEntry.getRoot() );
        allSources.addAll(pathEntry.getSources());
      }
      singleModule.setSourcePath(allSources);

//      pushModule(singleModule); // Push and leave pushed (in this thread)
      singleModule.update();
      singleModule.initializeTypeLoaders();
      CommonServices.getCoercionManager().init();

      startSneakyDebugThread();
    } finally {
      _state = TypeSystemState.STARTED;
    }
  }

  public void uninitializeDefaultSingleModule() {
    _state = TypeSystemState.STOPPING;
    try {
      if (_defaultModule != null) {
        DefaultSingleModule m = (DefaultSingleModule) _defaultModule;
        m.getModuleTypeLoader().uninitializeTypeLoaders();
        m.getModuleTypeLoader().reset();
        m.reset();
      }
      _modules.clear();

    } finally {
      _state = TypeSystemState.STOPPED;
    }
  }

  public void initializeMultipleModules(List<? extends IModule> modules) {
    _state = TypeSystemState.STARTING;
    try {
      // noinspection unchecked
      _defaultModule = null;
      _rootModule = null;
      _modules = (List<IModule>) modules;

      for (IModule module : modules) {
        module.update();
      }

      for (IModule module : modules) {
        pushModule(module);
        try {
          ((Module) module).initializeTypeLoaders();
        } finally {
          popModule(module);
        }
      }

      CommonServices.getCoercionManager().init();

      FrequentUsedJavaTypeCache.instance( this ).init();
    } finally {
      _state = TypeSystemState.STARTED;
    }
  }

  public void uninitializeMultipleModules() {
    _state = TypeSystemState.STOPPING;
    try {
      TypeSystem.shutdown( this, false);

      for (IModule module : _modules) {
        ((Module) module).getModuleTypeLoader().uninitializeTypeLoaders();
      }

      _jreModule = null;
      _rootModule = null;

      _modules.clear();
    } finally {
      _state = TypeSystemState.STOPPED;
    }
  }

  public void addModule(IModule module) {
    checkForDuplicates(module.getName());
    // noinspection unchecked
    _modules.add(module);
    module.update();

    pushModule(module);
    try {
      ((Module) module).initializeTypeLoaders();
    } finally {
      popModule(module);
    }
  }

  void checkForDuplicates(String moduleName) {
    for (IModule m : getModules()) {
      if (m.getName().equals(moduleName)) {
        throw new RuntimeException("Module " + moduleName + " allready exists.");
      }
    }
  }

  public void removeModule(IModule module) {
    _modules.remove(module);
  }

  public void pushModule(IModule module) {
    if(module == null) {
      IllegalStateException ise = new IllegalStateException("Attempted to push NULL module on Gosu module stack:");
      ise.printStackTrace();
      throw ise;
    }
    TypeSystem.pushModule(module);
  }

  public void popModule(IModule module) {
    TypeSystem.popModule(module);
  }

  public IModule getCurrentModule() {
    return TypeSystem.getCurrentModule();
  }

  public IModule getModule(String strModuleName) {
    for (IModule m : _modules) {
      if (m.getName().equals(strModuleName)) {
        return m;
      }
    }
    if( isSingleModuleMode() && GLOBAL_MODULE_NAME.equals( strModuleName ) ) {
      return getGlobalModule();
    }
    return null;
  }

  public IModule getModule( IResource file ) {
    List<? extends IModule> modules = getModules();
    if (modules.size() == 1) {
      return modules.get(0); // single module
    }

    for ( IModule module : modules) {
      if (module != _rootModule) {
        if (isInModule(module, file)) {
          return module;
        }
      }
    }

    if (isInModule(_rootModule, file)) {
      return _rootModule;
    }

    return null;
  }

  private boolean isInModule(IModule module, IResource file) {
    for (IDirectory src : module.getSourcePath()) {
      if (file.equals(src) || file.isDescendantOf(src)) {
        return true;
      }
    }
    return false;
  }

  public IModule getModule( URL url ) {
    return getModule( CommonServices.getFileSystem().getIFile( url ) );
  }

  @Override
  public void createJreModule( boolean includesGosuCoreAPI )
  {
    _jreModule = new JreModule( this, includesGosuCoreAPI );
  }

  @Override
  public void updateAllModules() {
    List<? extends IModule> modules = getModules();
    for (IModule module : modules) {
      module.update();
    }
  }

  /**
   * @return The module responsible for resolving JRE core classes e.g.,
   *         java.lang.* etc. Note in default single module environment this is
   *         the single module, otherwise this is the module create by calling createJreModule().
   *         This method will never return null but it will throw an NPE if the JRE module is null.
   */
  public IModule getJreModule() {
    if (_jreModule == null) {
      if (isSingleModuleMode()) {
        _jreModule = getGlobalModule();
      } else {
        throw new RuntimeException("The JRE module was not created. Please create it before trying to get it.");
      }
    }
    return _jreModule;
  }

  public boolean isSingleModuleMode()
  {
    return _modules != null && _modules.size() == 1 && _modules.get(0) instanceof DefaultSingleModule;
  }

  public static boolean isDefaultSingleModuleMode()
  {
    if( INSTANCES.size() == 1 )
    {
      if( THE_ONE != null )
      {
        return THE_ONE.isSingleModuleMode();
      }
      Collection<ExecutionEnvironment> values = INSTANCES.values();
      return values.iterator().next().isSingleModuleMode();
    }
    return false;
  }

  public IModule getGlobalModule() {
    if (_rootModule == null) {
      String moduleName = System.getProperty("GW_ROOT_MODULE");
      if (moduleName != null) {
        _rootModule = getModule(moduleName);
        if (_rootModule == null) {
          throw new RuntimeException("The specified root module '" + moduleName +"' does not exist.");
        }
      } else {
        _rootModule = findRootModule();
      }
    }
    return _rootModule;
  }

  public IModule findRootModule() {
    List<IModule> moduleRoots = new ArrayList<IModule>(_modules);
    for (IModule module : _modules) {
      for (Dependency d : module.getDependencies()) {
        moduleRoots.remove(d.getModule());
      }
    }
    return moduleRoots.size() > 0 ? moduleRoots.get(0) : null;
  }

  public TypeSystemState getState() {
    return _state;
  }

  public void renameModule(IModule module, String newName) {
    ((ExecutionEnvironment)module.getExecutionEnvironment()).checkForDuplicates(newName);
    ((Module) module).setName(newName);
  }

  @Override
  public String makeGosucProjectFile( String projectClassName ) {
    try {
      Class prjClass;
      try {
        prjClass = Class.forName( projectClassName );
      }
      catch( ClassNotFoundException cnfe ) {
        // Default
        prjClass = GosucProject.class;
      }
      GosucProject gosucProject = (GosucProject)prjClass.getConstructor( IExecutionEnvironment.class ).newInstance( this );
      return gosucProject.write();
    }
    catch( Exception e ) {
      throw new RuntimeException( e );
    }
  }

  public void shutdown() {
    for (IModule module : _modules) {
      module.getModuleTypeLoader().shutdown();
    }
    INSTANCES.clear();
    THE_ONE = null;
  }

  private static class DefaultSingleModuleRuntimeProject implements IProject
  {
    @Override
    public String getName()
    {
      return getClass().getSimpleName();
    }

    @Override
    public Object getNativeProject()
    {
      return this;
    }

    @Override
    public boolean isDisposed()
    {
      return false;
    }

    @Override
    public String toString()
    {
      return "Default Single Runtime Execution Environment";
    }

    @Override
    public boolean isHeadless()
    {
      return false;
    }
  }

  /**
   * Detect whether or not the jdwp agent is alive in this process, if so start
   * a thread that wakes up every N seconds and checks to see if the ReloadClassesIndicator
   * Java class has been redefined by a debugger.  If so, it reloads Gosu classes
   * that have changed.
   * <p>
   * Wtf, you ask?  Well since Gosu classes are not compiled to disk, the IDE hosting
   * Gosu can't simply send the bytes in a conventional JDI redefineClasses() call.
   * Yet it somehow needs to at least inform Gosu's type system in the target process
   * that Gosu classes have changed.  The JVMTI doesn't offer much help; there's no
   * way to field an arbitrary call from the JDWP client, or for the client to send an
   * arbitrary message.  Nor is it possible to leverage the JVMTI's ability to handle
   * method invocation etc. because the target thread must be suspended at a
   * breakpoint, which is not necessarily the case during compilation, and certainly
   * isn't the case for a thread dedicated to fielding such a call.  What to do?
   * <p>
   * We can leverage redefineClasses() after all.  The idea is for the IDE compiler
   * to redefine a class (via asm) designated as the "ReloadClassIndicator".  This class lives
   * inside Gosu's type system.  It has a single method: public static long timestamp()
   * and returns a literal value.  If the target process is being debugged (jdwp
   * agent detection), a thread in the target process starts immediately and waits a
   * few seconds before calling the timestamp() method, it does this in a forever loop.
   * If the timestamp value changes, we assume the IDE redefined the class with a new
   * value to indicate classes have changed.  In turn we find and reload changed
   * classes.  What could be more straight forward?
   * <p>
   * An alternative approach would be for the IDE to establish an additional line
   * of communication with the target process e.g., socket, memory, whatever.  But
   * that is messy (requires config on user's end) and error prone.  One debug
   * socket is plenty.
   * <p>
   * Improvements to this strategy include supplying not only an indication that stuff
   * has changed, but also the names of the classes that have changed.  This would
   * releive the target process from having to keep track timestamps on all loaded
   * classes. This could be implemented by having the class return an array of names.
   * An even better improvement would be to include not just the names, but also the
   * source of the classes.  This would enable the debuger to modify in memory the classes
   * during a remote debugging session.
   */
  private void startSneakyDebugThread() {
    if( !BytecodeOptions.JDWP_ENABLED.get() ) {
      return;
    }
    Thread sneakyDebugThread =
        new Thread(
            new Runnable() {
              public synchronized void run() {
                long timestamp = ReloadClassesIndicator.timestamp();
                long now = 0;
                while (getState() != TypeSystemState.STOPPED) {
                  try {
                    wait(2000);
                    now = ReloadClassesIndicator.timestamp();
                    if (now > timestamp) {
                      String script = ReloadClassesIndicator.getScript();
                      if (script != null && script.length() > 0) {
                        runScript(script);
                      }
                      else {
                        refreshTypes();
                      }
                    }
                  } catch (Exception e) {
                    e.printStackTrace();
                  } finally {
                    timestamp = now;
                  }
                }
              }

              private void refreshTypes() {
                String[] types = ReloadClassesIndicator.changedTypes();
                System.out.println("Refreshing " + types.length + " types at " + new Date());
                if (types.length > 0) {
                  TypeSystem.refreshTypesByName(types, TypeSystem.getGlobalModule(), true);
                }
              }

              private void runScript( String strScript ) {
                String[] result = evaluate(strScript);
                if( result[0] != null && result[0].length() > 0 )
                {
                  System.out.print( result[0] );
                }
                if( result[1] != null && result[1].length() > 0 )
                {
                  System.err.print( result[1] );
                }
              }

              public String[] evaluate( String strScript )
              {
                IGosuParser scriptParser = GosuParserFactory.createParser(strScript);

                try
                {
                  IGosuProgramParser programParser = GosuParserFactory.createProgramParser();
                  ParserOptions options = new ParserOptions().withParser( scriptParser );
                  IParseResult parseResult = programParser.parseExpressionOrProgram( strScript, scriptParser.getSymbolTable(), options );
                  Object result = parseResult.getProgram().evaluate( null );
                  if( result != null )
                  {
                    System.out.println( "Return Value: " + CommonServices.getCoercionManager().convertValue( result, JavaTypes.STRING() ) );
                  }
                }
                catch( Exception e )
                {
                  boolean print = true;
                  Throwable t = e;
                  while( t != null )
                  {
                    if( t instanceof SystemExitIgnoredException)
                    {
                      print = false;
                    }
                    t = t.getCause();
                  }
                  if( print )
                  {
                    assert e != null;
                    e.printStackTrace();
                  }
                }
                return new String[]{null, null};
              }
            }, CLASS_REDEFINER_THREAD);
    sneakyDebugThread.setDaemon(true);
    sneakyDebugThread.start();
  }

}
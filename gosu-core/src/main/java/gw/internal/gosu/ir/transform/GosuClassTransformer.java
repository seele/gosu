/*
 * Copyright 2012. Guidewire Software, Inc.
 */

package gw.internal.gosu.ir.transform;

import gw.internal.ext.org.objectweb.asm.Opcodes;
import gw.internal.gosu.annotations.AnnotationMap;
import gw.internal.gosu.ir.nodes.IRMethod;
import gw.internal.gosu.ir.nodes.IRMethodFactory;
import gw.internal.gosu.ir.nodes.JavaClassIRType;
import gw.internal.gosu.ir.transform.statement.FieldInitializerTransformer;
import gw.internal.gosu.ir.transform.util.AccessibilityUtil;
import gw.internal.gosu.ir.transform.util.IRTypeResolver;
import gw.internal.gosu.ir.transform.util.NameResolver;
import gw.internal.gosu.parser.AbstractDynamicSymbol;
import gw.internal.gosu.parser.AnnotationBuilder;
import gw.internal.gosu.parser.BlockClass;
import gw.internal.gosu.parser.DynamicFunctionSymbol;
import gw.internal.gosu.parser.EnumCodePropertySymbol;
import gw.internal.gosu.parser.EnumDisplayNamePropertySymbol;
import gw.internal.gosu.parser.EnumNamePropertySymbol;
import gw.internal.gosu.parser.EnumValueOfFunctionSymbol;
import gw.internal.gosu.parser.EnumValuesFunctionSymbol;
import gw.internal.gosu.parser.GosuAnnotationInfo;
import gw.internal.gosu.parser.GosuClass;
import gw.internal.gosu.parser.ICompilableTypeInternal;
import gw.internal.gosu.parser.IGosuClassInternal;
import gw.internal.gosu.parser.IGosuEnhancementInternal;
import gw.internal.gosu.parser.IGosuTemplateInternal;
import gw.internal.gosu.parser.IJavaTypeInternal;
import gw.internal.gosu.parser.MemberFieldSymbol;
import gw.internal.gosu.parser.ParameterizedDynamicFunctionSymbol;
import gw.internal.gosu.parser.Symbol;
import gw.internal.gosu.parser.TemplateRenderFunctionSymbol;
import gw.internal.gosu.parser.TypeLord;
import gw.internal.gosu.parser.statements.ClassStatement;
import gw.internal.gosu.parser.statements.StatementList;
import gw.internal.gosu.parser.statements.VarStatement;
import gw.internal.gosu.runtime.GosuRuntimeMethods;
import gw.lang.ir.IRAnnotation;
import gw.lang.ir.IRClass;
import gw.lang.ir.IRExpression;
import gw.lang.ir.IRStatement;
import gw.lang.ir.IRSymbol;
import gw.lang.ir.IRType;
import gw.lang.ir.IRTypeConstants;
import gw.lang.ir.SyntheticIRType;
import gw.lang.ir.expression.IRMethodCallExpression;
import gw.lang.ir.statement.IRFieldDecl;
import gw.lang.ir.statement.IRMethodCallStatement;
import gw.lang.ir.statement.IRMethodStatement;
import gw.lang.ir.statement.IRNoOpStatement;
import gw.lang.ir.statement.IRReturnStatement;
import gw.lang.ir.statement.IRStatementList;
import gw.lang.parser.CaseInsensitiveCharSequence;
import gw.lang.parser.ICapturedSymbol;
import gw.lang.parser.IDynamicFunctionSymbol;
import gw.lang.parser.IDynamicPropertySymbol;
import gw.lang.parser.IProgramClassFunctionSymbol;
import gw.lang.parser.IStatement;
import gw.lang.parser.ISymbol;
import gw.lang.parser.Keyword;
import gw.lang.parser.expressions.IVarStatement;
import gw.lang.parser.statements.IFunctionStatement;
import gw.lang.reflect.IAnnotationInfo;
import gw.lang.reflect.IMethodInfo;
import gw.lang.reflect.IParameterInfo;
import gw.lang.reflect.IRelativeTypeInfo;
import gw.lang.reflect.IType;
import gw.lang.reflect.Modifier;
import gw.lang.reflect.TypeSystem;
import gw.lang.reflect.gs.BytecodeOptions;
import gw.lang.reflect.gs.GosuClassPathThing;
import gw.lang.reflect.gs.IExternalSymbolMap;
import gw.lang.reflect.gs.IGenericTypeVariable;
import gw.lang.reflect.gs.IGosuClass;
import gw.lang.reflect.gs.IGosuProgram;
import gw.lang.reflect.java.IJavaClassInfo;
import gw.lang.reflect.java.IJavaClassMethod;
import gw.lang.reflect.java.IJavaMethodInfo;
import gw.lang.reflect.java.IJavaType;
import gw.lang.reflect.java.JavaTypes;
import gw.util.GosuExceptionUtil;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 */
public class GosuClassTransformer extends AbstractElementTransformer<ClassStatement>
{
  public static final String ENUM_VALUES_FIELD = "ENUM$VALUES";

  private IGosuClassInternal _gsClass;
  private EnumOrdinalCounter _enumCounter;
  private IRClass _irClass;
  private GosuClassTransformationContext _context;

  public static IRClass compile( IGosuClassInternal gsClass )
  {
    GosuClassTransformer cc = new GosuClassTransformer( gsClass );
    return cc.compile();
  }

  public static IRClass compileInterfaceMethodsClass( IGosuClassInternal gsClass )
  {
    GosuClassTransformer cc = new GosuClassTransformer( gsClass );
    return cc.compileInterfaceMethodsClass();
  }

  private GosuClassTransformer( IGosuClassInternal gsClass )
  {
    super( null, (ClassStatement)gsClass.getClassStatement() );
    _gsClass = gsClass;
    _enumCounter = new EnumOrdinalCounter();
    _context = new GosuClassTransformationContext( this, _gsClass );
    setCc( _context );
  }

  private IRClass compile()
  {
    if( !_gsClass.isValid() )
    {
      //noinspection ThrowableResultOfMethodCallIgnored
      throw GosuExceptionUtil.forceThrow( _gsClass.getParseResultsException() );
    }

    _irClass = new IRClass();

    compileClassHeader();

    addSourceFileRef();

    compileInnerClasses();

    compileStaticInitializer();

    compileFields();

    compileConstructors();

    compileMethods();

    addAnnotations();

    return _irClass;
  }

  public IRClass compileInterfaceMethodsClass()
  {
    if( getGosuClass().isInterface() )
    {
      _irClass = new IRClass();

      //simplified header
      _irClass.setName( _gsClass.getInterfaceMethodsClassName() );
      _irClass.setThisType( new SyntheticIRType( Object.class,
                                                 _gsClass.getInterfaceMethodsClassName(),
                                                 GosuClass.ANNOTATION_METHODS_FOR_INTERFACE_INNER_CLASS ) );
      _irClass.setModifiers( Opcodes.ACC_PUBLIC );
      _irClass.setSuperType( getDescriptor( Object.class ) );

      addSourceFileRef();

      addEvalAnnotationMethod( _irClass );

      return _irClass;
    }
    else
    {
      throw new IllegalStateException( "Cannot create an interface methods class for a non-interface" );
    }
  }


  private void addAnnotations()
  {
    List<IRAnnotation> annotations = getIRAnnotations( (List)_gsClass.getTypeInfo().getAnnotations() );
    _irClass.setAnnotations( annotations );
  }

  private List<IRAnnotation> getIRAnnotations( List<? extends IAnnotationInfo> gosuAnnotations )
  {
    List<IRAnnotation> annotations = new ArrayList<IRAnnotation>();
    Set<IType> alreadyCreated = new HashSet<IType>();
    for( IAnnotationInfo ai : gosuAnnotations )
    {
      if( ai instanceof GosuAnnotationInfo )
      {
        GosuAnnotationInfo gai = (GosuAnnotationInfo)ai;
        IType type = ai.getType();
        if( BytecodeOptions.isGenerateAnnotationsToClassFiles() &&
            !alreadyCreated.contains(type) &&
            gai.getRawAnnotation().shouldPersistToClass() )
        {
          alreadyCreated.add( ai.getType() );
          annotations.add( new IRAnnotation( getDescriptor( ai.getType() ),
                                             gai.getRawAnnotation().shouldRetainAtRuntime(), ai ) );
        }
      }
      else
      {
        IType type = ai.getType();
        if( BytecodeOptions.isGenerateAnnotationsToClassFiles() &&
            !alreadyCreated.contains( type ) &&
            hasRetentionPolicy( ai, RetentionPolicy.SOURCE ) )
        {
          alreadyCreated.add( ai.getType() );
          annotations.add( new IRAnnotation( getDescriptor( ai.getType() ),
                                             hasRetentionPolicy( ai, RetentionPolicy.RUNTIME ), ai ) );
        }
      }
    }
    return annotations;
  }

  private boolean hasRetentionPolicy( IAnnotationInfo annotation, RetentionPolicy policy )
  {
    List<IAnnotationInfo> annotationsOfType = annotation.getType().getTypeInfo().getAnnotationsOfType( TypeSystem.get( Retention.class ) );
    for( IAnnotationInfo retention : annotationsOfType )
    {
      if( retention != null && retention.getFieldValue( "value" ).equals( policy.name() ) )
      {
        return true;
      }
    }
    return false;
  }

  private void compileInnerClasses()
  {
    if( _gsClass.getEnclosingType() != null )
    {
      IGosuClassInternal thisInnerClass = _gsClass;
      visitInnerClass( thisInnerClass );
    }

    for( IGosuClass innerClass : _gsClass.getInnerClasses() )
    {
      visitInnerClass( innerClass );
    }
  }

  private void visitInnerClass( IGosuClass innerClass )
  {
    _irClass.addInnerClass( getDescriptor( innerClass ),
                            getDescriptor( innerClass.getEnclosingType() ),
                            getClassModifiers( innerClass, true ) );
  }

  private void compileStaticInitializer()
  {
    List<IRStatement> initStatements = new ArrayList<IRStatement>();
    IRMethodCallExpression bootstrapGosuWhenInitiatedViaClassfile = buildMethodCall( JavaClassIRType.get( GosuClassPathThing.class ), "init", false, IRTypeConstants.pBOOLEAN, Collections.<IRType>emptyList(), null, Collections.<IRExpression>emptyList() );
    initStatements.add( new IRMethodCallStatement( bootstrapGosuWhenInitiatedViaClassfile ) );
    List<IRSymbol> syms = new ArrayList<IRSymbol>( 1 );
    if( isProgramOrEnclosedInProgram( getGosuClass() ) )
    {
      //## hack:  Because we pass in the external symbols map everywhere through this cluster labyrinth of intermediate glory
      // (instead of grabbing it from the program like we used to and should still be doing)
      // we are stuck with this hack and have no way of actually getting the params.
      IRSymbol symbolsParam = new IRSymbol( GosuFragmentTransformer.SYMBOLS_PARAM_NAME, getDescriptor( IExternalSymbolMap.class ), true );
      IRStatement nullExternalSymbols = buildAssignment( symbolsParam, pushNull() );
      initStatements.add( nullExternalSymbols );
      syms.add( symbolsParam );
    }
    setUpFunctionContext( false, syms );
    initializeStaticFields( initStatements );
    if( initStatements.size() > 0 )
    {
      initStatements.add( new IRReturnStatement() );
      IRMethodStatement clinit = new IRMethodStatement( new IRStatementList( false, initStatements ),
                                                        "<clinit>", Opcodes.ACC_STATIC, IRTypeConstants.pVOID, Collections.<IRSymbol>emptyList() );
      _irClass.addMethod( clinit );
    }
  }

  private void compileFields()
  {
    addInstanceFields();
    addStaticFields();
    addOuterThisField();
    addCapturedSymbolFields();
    addTypeParamFields();
  }

  private void addInstanceFields()
  {
    for( IVarStatement field : getOrderedFields() )
    {
      IRFieldDecl fieldDecl = new IRFieldDecl( getModifiers( (AbstractDynamicSymbol)field.getSymbol() ),
                                               field.getIdentifierName().toString(),
                                               getDescriptor( field.getType() ),
                                               null );
      fieldDecl.setAnnotations( getIRAnnotations( makeAnnotationInfos( ((VarStatement)field).getGosuAnnotations(), getGosuClass().getTypeInfo() ) ) );
      _irClass.addField( fieldDecl );
    }
  }

  private void addStaticFields()
  {
    for( IVarStatement field : _gsClass.getStaticFields() )
    {
      IRFieldDecl fieldDecl = new IRFieldDecl( getModifiers( (Symbol)field.getSymbol() ),
                                               field.getIdentifierName().toString(),
                                               getDescriptor( field.getType() ),
                                               null );
      fieldDecl.setAnnotations( getIRAnnotations( makeAnnotationInfos( ((VarStatement)field).getGosuAnnotations(), getGosuClass().getTypeInfo() ) ) );
      _irClass.addField( fieldDecl );
    }

    // Enums automatically get a synthetic field that holds an array of all enum values
    // in the appropriate order and which is used to implement values() method.  The field
    // is intialized statically, immediately after all the enum constants have been initialized.
    if( _gsClass.isEnum() )
    {
      int iModifiers = Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC;
      iModifiers |= (BytecodeOptions.isSingleServingLoader() ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PRIVATE);
      IRFieldDecl fieldDecl = new IRFieldDecl( iModifiers,
                                               ENUM_VALUES_FIELD,
                                               getDescriptor( _gsClass.getArrayType() ),
                                               null );
      _irClass.addField( fieldDecl );
    }
  }

  private void addOuterThisField()
  {
    if( isNonStaticInnerClass() && !_gsClass.isInterface() )
    {
      int iModifiers = Opcodes.ACC_FINAL + Opcodes.ACC_SYNTHETIC;
      iModifiers |= (BytecodeOptions.isSingleServingLoader() ? Opcodes.ACC_PUBLIC : 0);
      IRFieldDecl fieldDecl = new IRFieldDecl( iModifiers,
                                               getOuterThisFieldName(),
                                               getDescriptor( getRuntimeEnclosingType( _gsClass ) ),
                                               null );
      _irClass.addField( fieldDecl );
    }
  }

  private void addCapturedSymbolFields()
  {
    Map<CaseInsensitiveCharSequence, ICapturedSymbol> capturedSymbols = _gsClass.getCapturedSymbols();

    if( capturedSymbols != null )
    {
      for( ICapturedSymbol sym : capturedSymbols.values() )
      {
        int iModifiers = Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC;
        iModifiers |= (BytecodeOptions.isSingleServingLoader() ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PRIVATE);
        IRFieldDecl fieldDecl = new IRFieldDecl( iModifiers,
                                                 CAPTURED_VAR_PREFIX + sym.getName(),
                                                 getDescriptor( sym.getType().getArrayType() ),
                                                 null );
        _irClass.addField( fieldDecl );
      }
    }

    if( requiresExternalSymbolCapture( _gsClass ) )
    {
      int iModifiers = Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC;
      iModifiers |= (BytecodeOptions.isSingleServingLoader() ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PRIVATE);
      IRFieldDecl fieldDecl = new IRFieldDecl( iModifiers,
                                               GosuFragmentTransformer.SYMBOLS_PARAM_NAME,
                                               getDescriptor( IExternalSymbolMap.class ),
                                               null );
      _irClass.addField( fieldDecl );
    }
  }

  private void addTypeParamFields()
  {
    IGosuClassInternal gsClass = _gsClass;

    if( gsClass.isGenericType() && !gsClass.isInterface() )
    {
      for( IGenericTypeVariable genTypeVar : gsClass.getGenericTypeVariables() )
      {
        int iModifiers = Opcodes.ACC_FINAL + Opcodes.ACC_SYNTHETIC;
        iModifiers |= (BytecodeOptions.isSingleServingLoader() ? Opcodes.ACC_PUBLIC : 0);
        IRFieldDecl fieldDecl = new IRFieldDecl( iModifiers,
                                                 TYPE_PARAM_PREFIX + genTypeVar.getName(),
                                                 getDescriptor( IType.class ),
                                                 null );
        _irClass.addField( fieldDecl );
      }
    }

    while( gsClass.isAnonymous() )
    {
      IDynamicFunctionSymbol dfs = getEnclosingDFS( gsClass );
      if( dfs == null )
      {
        break;
      }
      for( IGenericTypeVariable genTypeVar : getTypeVarsForDFS( dfs ) )
      {
        int iModifiers = Opcodes.ACC_FINAL + Opcodes.ACC_SYNTHETIC;
        iModifiers |= (BytecodeOptions.isSingleServingLoader() ? Opcodes.ACC_PUBLIC : 0);
        IRFieldDecl fieldDecl = new IRFieldDecl( iModifiers,
                                                 TYPE_PARAM_PREFIX + genTypeVar.getName(),
                                                 getDescriptor( IType.class ),
                                                 null );
        _irClass.addField( fieldDecl );
      }
      gsClass = (IGosuClassInternal)dfs.getGosuClass();
    }
  }

  public boolean isNonStaticInnerClass()
  {
    return !_gsClass.isStatic() && _gsClass.getEnclosingType() != null;
  }

  public String getOuterThisFieldName()
  {
    return Keyword.KW_this + "$" + (_gsClass.getDepth() - 1);
  }

  private void compileConstructors()
  {
    for( DynamicFunctionSymbol dfs : _gsClass.getConstructorFunctions() )
    {
      int iModifiers = getModifiers( dfs );

      List<IRSymbol> parameters = new ArrayList<IRSymbol>();
      maybeGetOuterThisParamType( parameters );
      maybeGetCapturedSymbolTypes( parameters );
      maybeGetTypeVarSymbolTypesForConstructor( parameters );
      maybeGetEnumSuperConstructorSymbols( parameters );
      for( ISymbol param : dfs.getArgs() )
      {
        String name = param.getName();
        if( isBlockInvoke( dfs ) )
        {
          name = name + "$$blockParam";
        }
        else if( param.isValueBoxed() )
        {
          name = name + "$$unboxedParam";
        }
        parameters.add( new IRSymbol( name, getDescriptor( param.getType() ), false ) );
      }

      IRStatement methodBody;
      IFunctionStatement stmt = dfs.getDeclFunctionStmt();
      if( stmt == null && !_gsClass.isAnonymous() ) // Anonymous classes always have one constructor with no declaration, but they might need explicit compilation due to the super() call therein
      {
        setUpFunctionContext( true, parameters );
        methodBody = compileDefaultCtorBody();
      }
      else
      {
        setUpFunctionContext( dfs, true, parameters );
        FunctionStatementTransformer funcStmtCompiler = new FunctionStatementTransformer( dfs, _context );
        methodBody = funcStmtCompiler.compile();
      }

      IRMethodStatement methodStatement = new IRMethodStatement(
        methodBody,
        "<init>",
        iModifiers,
        IRTypeConstants.pVOID,
        parameters );

      _irClass.addMethod( methodStatement );
    }
  }

  private void maybeGetTypeVarSymbolTypesForConstructor( List<IRSymbol> parameters )
  {
    if( _gsClass.isGenericType() )
    {
      addTypeParamDescriptor( parameters, Arrays.asList( _gsClass.getGenericTypeVariables() ) );
    }

    appendTypeVarsFromEnclosingFunctions( parameters, _gsClass );
  }

  private void maybeGetEnumSuperConstructorSymbols( List<IRSymbol> parameters )
  {
    if( _gsClass.isEnum() )
    {
      parameters.add( new IRSymbol( ENUM_PARAM_PREFIX + "name", getDescriptor( String.class ), false ) );
      parameters.add( new IRSymbol( ENUM_PARAM_PREFIX + "ordinal", getDescriptor( int.class ), false ) );
    }
  }

  private void maybeAddImplicitEnhancementParameters( DynamicFunctionSymbol dfs, List<IRSymbol> parameters )
  {
    if( isCompilingEnhancement() && !dfs.isStatic() )
    {
      parameters.add( new IRSymbol( ENHANCEMENT_THIS_REF, getDescriptor( getGosuEnhancement().getEnhancedType() ), false ) );
    }
  }

  private void maybeAddImplicitExternalSymbolsParameter( DynamicFunctionSymbol dfs, List<IRSymbol> parameters )
  {
    if( ((_cc().getGosuClass() instanceof IGosuProgram) ||
         (dfs.isStatic() && isProgramOrEnclosedInProgram( _cc().getGosuClass() ))) &&
        !(dfs instanceof IProgramClassFunctionSymbol) &&
        !(dfs instanceof TemplateRenderFunctionSymbol) )
    {
      parameters.add( new IRSymbol( GosuFragmentTransformer.SYMBOLS_PARAM_NAME, getDescriptor( IExternalSymbolMap.class ), false ) );
    }
  }

  private void maybeGetTypeVarSymbolTypes( DynamicFunctionSymbol dfs, List<IRSymbol> parameters )
  {
    addTypeParamDescriptor( parameters, getTypeVarsForDFS( dfs ) );
  }


  private void addTypeParamDescriptor( List<IRSymbol> parameters, List<IGenericTypeVariable> genTypeVars )
  {
    for( int i = 0; i < genTypeVars.size(); i++ )
    {
      parameters.add( new IRSymbol( getTypeVarParamName( genTypeVars.get( i ) ), getDescriptor( IType.class ), false ) );
    }
  }

  private void appendTypeVarsFromEnclosingFunctions( List<IRSymbol> parameters, IGosuClassInternal gsClass )
  {
    while( gsClass.isAnonymous() )
    {
      IDynamicFunctionSymbol dfs = getEnclosingDFS( gsClass );
      if( dfs == null )
      {
        break;
      }
      addTypeParamDescriptor( parameters, getTypeVarsForDFS( dfs ) );
      gsClass = (IGosuClassInternal)dfs.getGosuClass();
    }
  }

  private void maybeGetCapturedSymbolTypes( List<IRSymbol> parameters )
  {
    Map<CaseInsensitiveCharSequence, ICapturedSymbol> capturedSymbols = _gsClass.getCapturedSymbols();
    if( capturedSymbols != null )
    {
      for( ICapturedSymbol sym : capturedSymbols.values() )
      {
        parameters.add( new IRSymbol( getCapturedSymbolParameterName( sym ), getDescriptor( sym.getType().getArrayType() ), false ) );
      }
    }

    // The external symbols map is itself always considered captured
    if( requiresExternalSymbolCapture( _gsClass ) )
    {
      parameters.add( new IRSymbol( GosuFragmentTransformer.SYMBOLS_PARAM_NAME + "arg", getDescriptor( IExternalSymbolMap.class ), true ) );
    }
  }

  private void maybeGetOuterThisParamType( List<IRSymbol> parameters )
  {
    if( isNonStaticInnerClass() )
    {
      IType enclosingType = getRuntimeEnclosingType( _gsClass );
      parameters.add( new IRSymbol( _context.getOuterThisParamName(), getDescriptor( enclosingType ), false ) );
    }
  }

  private IRStatement compileDefaultCtorBody()
  {
    List<IRStatement> statements = new ArrayList<IRStatement>();
    maybeAssignOuterRef( statements );
    initCapturedSymbolFields( statements );
    initTypeVarFields( statements );
    List<IRExpression> superArgs = new ArrayList<IRExpression>();
    maybePushSupersEnclosingThisRef( superArgs );

    pushCapturedSymbols( _cc().getSuperType(), superArgs, false );
    int iTypeParams = pushTypeParametersForConstructor( null, _cc().getSuperType(), superArgs );
    pushEnumSuperConstructorArguments( superArgs );
    IType[] superParameterTypes = IType.EMPTY_ARRAY;
    if( _gsClass.isEnum() )
    {
      // If the super type is Enum, it explicitly takes a String and an int
      superParameterTypes = new IType[]{JavaTypes.STRING(), JavaTypes.pINT()};
    }
    IRType[] params = getConstructorParamTypes( superParameterTypes, iTypeParams, _cc().getSuperType() );

    IRMethod irMethod = IRMethodFactory.createConstructorIRMethod( _cc().getSuperType(), params );
    statements.add( new IRMethodCallStatement( callSpecialMethod( getDescriptor( _cc().getSuperType() ), irMethod, pushThis(), superArgs ) ) );

    initializeInstanceFields( statements );
    statements.add( new IRReturnStatement() );

    return new IRStatementList( false, statements );
  }

  public void maybeAssignOuterRef( List<IRStatement> statements )
  {
    if( isNonStaticInnerClass() )
    {
      // Inner class' outer ref e.g., 'this$0'
      statements.add( setInstanceField(
        _gsClass, getOuterThisFieldName(), getDescriptor( getRuntimeEnclosingType( _gsClass ) ), AccessibilityUtil.forOuter(),
        pushThis(),
        identifier( _context.getSymbol( _context.getOuterThisParamName() ) ) ) );
    }
  }

  public void maybePushSupersEnclosingThisRef( List<IRExpression> arguments )
  {
    IType superType = _gsClass.getSupertype();
    if( isNonStaticInnerClass( superType ) )
    {
      IGosuClass typeToPass = (IGosuClass)superType.getEnclosingType();
      ICompilableTypeInternal outerType = _gsClass.getEnclosingType();
      if( outerType == typeToPass || (typeToPass != null && outerType != null && typeToPass.isAssignableFrom( outerType )) )
      {
        arguments.add( identifier( _context.getSymbol( _context.getOuterThisParamName() ) ) );
      }
      else
      {
        do
        {
//          _mv.visitVarInsn( Opcodes.ALOAD, 1 );
//          callStaticMethod(
//            outerType, getAccessibilityForOuter(), OUTER_ACCESS, outerType.getEnclosingType(), outerType );
          outerType = outerType.getEnclosingType();
        } while( outerType != typeToPass );
      }
    }
  }

  private void compileMethods()
  {
    for( IDynamicFunctionSymbol idfs : _gsClass.getStaticFunctions() )
    {
      compileMethod( (DynamicFunctionSymbol)idfs );
    }

    // Create an ordered set from the list because the list may contain duplicates e.g., same dfs for super and overridden generic method.
    LinkedHashSet<IDynamicFunctionSymbol> methodSet = new LinkedHashSet<IDynamicFunctionSymbol>( _gsClass.getMemberFunctions() );
    for( IDynamicFunctionSymbol idfs : methodSet )
    {
      compileMethod( (DynamicFunctionSymbol)idfs );
      compileBridgeMethods( (DynamicFunctionSymbol)idfs );
    }

    if( !_gsClass.isInterface() && !isCompilingEnhancement() && !_cc().compilingBlock() )
    {
      compileIntrinsicTypePropertyGetter();
    }

    if( _gsClass.isEnum() )
    {
      compileEnumValuesMethod();
      compileEnumAllValuesPropertyGetter();
      compileEnumValueOfMethod();
      compileEnumValuePropertyGetter();
      compileEnumCodePropertyGetter();
      compileEnumOrdinalPropertyGetter();
      compileEnumDisplayNamePropertyGetter();
      compileEnumNamePropertyGetter();
    }

    if( isNonStaticInnerClass() && !_gsClass.isInterface() )
    {
      compileOuterAccessMethod();
    }

    compileMainMethod();
    compileAnnotationGetter();
  }

  /**
   * Generates a synthetic method if:
   * - method overrides with covariant return type
   * - for each in ancestry e.g., Square f() -> Rectangle f() -> Shape f().
   * - method overrides and specifies a concrete type for a parameter that is a
   * type variable in the super e.g., B extends A<String> where B#foo( o: String ) -> A#foo( o: T )
   */
  private void compileBridgeMethods( DynamicFunctionSymbol dfs )
  {
    if( dfs.isAbstract() )
    {
      return;
    }

    while( dfs.isOverride() )
    {
      DynamicFunctionSymbol superDfs = dfs.getSuperDfs();
      while( superDfs instanceof ParameterizedDynamicFunctionSymbol )
      {
        superDfs = ((ParameterizedDynamicFunctionSymbol)superDfs).getBackingDfs();
      }

      if( genProxyCovariantBridgeMethod( dfs, superDfs ) )
      {
        return;
      }

      IRType superRetDescriptor = getDescriptor( superDfs.getReturnType() );
      IRType overrideRetDescriptor = getDescriptor( dfs.getReturnType() );

      String superParamDescriptors = getParameterDescriptors( superDfs.getArgTypes() );
      String overrideParamDescriptors = getParameterDescriptors( dfs.getArgTypes() ); // e.g., foo( o: String ) -> foo( o: T )

      if( !superRetDescriptor.equals( overrideRetDescriptor ) ||
          !overrideParamDescriptors.equals( superParamDescriptors ) )
      {
        // The parameters include all type variable args and all parameters from the DFS
        List<IRSymbol> parameters = new ArrayList<IRSymbol>();
        maybeGetTypeVarSymbolTypes( superDfs, parameters );
        for( ISymbol arg : superDfs.getArgs() )
        {
          parameters.add( new IRSymbol( arg.getName(), getDescriptor( arg.getType() ), false ) );
        }

        setUpFunctionContext( superDfs, true, parameters );

        // The body of the method is just a call through to the method on this object
        List<IRExpression> methodCallArgs = new ArrayList<IRExpression>();
        maybePassTypeParams( dfs, methodCallArgs );
        for( int i = 0; i < dfs.getArgs().size(); i++ )
        {
          // The dfs and superDfs have to have the same number of args.  So we go through and pull out the parameter based on
          // the superDfs name, and cast it to the type of the dfs arg if it's not already compatible
          ISymbol dfsArg = dfs.getArgs().get( i );
          ISymbol superDfsArg = superDfs.getArgs().get( i );
          IRExpression arg = identifier( _context.getSymbol( superDfsArg.getName() ) );
          IRType expectedType = getDescriptor( dfsArg.getType() );
          if( !expectedType.isAssignableFrom( arg.getType() ) )
          {
            arg = buildCast( expectedType, arg );
          }
          methodCallArgs.add( arg );
        }

        IRMethod irMethod = IRMethodFactory.createIRMethod( _gsClass, NameResolver.getFunctionName( dfs ), dfs.getReturnType(), getParamsIncludingTypeParams( dfs ), AccessibilityUtil.forSymbol( dfs ), false );
        IRExpression methodCall = callMethod( irMethod, pushThis(), methodCallArgs );

        IRStatement methodBody;
        if( superDfs.getReturnType() != JavaTypes.pVOID() )
        {
          methodBody = new IRReturnStatement( null, methodCall );
        }
        else
        {
          methodBody = new IRStatementList( false,
                                            new IRMethodCallStatement( methodCall ),
                                            new IRReturnStatement()
          );
        }

        IRMethodStatement bridgeMethod = new IRMethodStatement(
          methodBody,
          NameResolver.getFunctionName( superDfs ),
          makeModifiersForBridgeMethod( getModifiers( dfs ) ),
          superRetDescriptor,
          parameters );
        _irClass.addMethod( bridgeMethod );

      }
      else
      {
        IGosuClassInternal gsProxyClass = superDfs.getGosuClass();
        if( gsProxyClass != null && gsProxyClass.isProxy() )
        {
          addCovarientProxyBridgeMethods( superDfs );
          break;
        }
      }
      dfs = superDfs;
    }
  }

  private int makeModifiersForBridgeMethod( int modifiers )
  {
    return (modifiers | Opcodes.ACC_BRIDGE | Opcodes.ACC_SYNTHETIC) & ~Opcodes.ACC_ABSTRACT;
  }

  /**
   * Add a bridge method for a Java interface method that is not only implemented
   * by a method in this Gosu class, but is also itself a covariant "override" of
   * its super interface E.g.,
   * <pre>
   * interface JavaBase {
   *  public CharSequence makeText();
   * }
   *
   * interface JavaSub extends JavaBase {
   *   public String makeText();
   * }
   *
   * class GosuSub extends JavaSub {
   *   function makeText() : String ...
   * }
   * </pre>
   * Here we need for this class to define a bridge method implementing JavaBase#makeText() : CharSequence
   */
  private void addCovarientProxyBridgeMethods( DynamicFunctionSymbol dfs )
  {
    IGosuClassInternal gsProxyClass = dfs.getGosuClass();
    if( gsProxyClass == null || !gsProxyClass.isProxy() )
    {
      // Not a proxy class so no java method to override
      return;
    }

    if( dfs.getReturnType().isPrimitive() )
    {
      // Void or primitive return means no covariant override possible
      return;
    }

    IJavaTypeInternal javaType = (IJavaTypeInternal)gsProxyClass.getJavaType();
    if( javaType.isInterface() )
    {
      IJavaClassMethod m = getMethodOverridableFromDfs( dfs, javaType.getBackingClassInfo() );
      if( m != null && Modifier.isAbstract( m.getModifiers() ) )
      {
        genInterfaceProxyBridgeMethod( m, javaType.getBackingClassInfo() );
      }
    }
  }

  private IJavaClassMethod getMethodOverridableFromDfs( DynamicFunctionSymbol dfs, IJavaClassInfo declaringClass )
  {
    String strName = dfs.getDisplayName();
    IJavaClassMethod m = null;
    if( strName.startsWith( "@" ) )
    {
      strName = strName.substring( 1 );
      try
      {
        m = declaringClass.getDeclaredMethod( "get" + strName, getClassInfos( dfs.getArgTypes() ) );
      }
      catch( NoSuchMethodException e )
      {
        try
        {
          m = declaringClass.getDeclaredMethod( "is" + strName, getClassInfos( dfs.getArgTypes() ) );
        }
        catch( NoSuchMethodException e1 )
        {
          // ignore
        }
      }
    }
    else
    {
      try
      {
        m = declaringClass.getDeclaredMethod( strName, getClassInfos( dfs.getArgTypes() ) );
      }
      catch( NoSuchMethodException e )
      {
        // ignore
      }
    }
    return m;
  }

  private void genInterfaceProxyBridgeMethod( IJavaClassMethod m, IJavaClassInfo iJavaClassInfo )
  {
    for( IJavaClassInfo iface : iJavaClassInfo.getInterfaces() )
    {
      try
      {
        IJavaClassMethod bridge = iface.getDeclaredMethod( m.getName(), m.getParameterTypes() );
        if( !bridge.getReturnType().equals( m.getReturnType() ) )
        {
          genBridgeMethod( bridge, m );
          m = bridge;
        }
      }
      catch( Exception e )
      {
        // ignore
      }
      genInterfaceProxyBridgeMethod( m, iface );
    }
  }

  private void genBridgeMethod( IJavaClassMethod bridge, IJavaClassMethod m )
  {
    IRType superRetDescriptor = getDescriptor( bridge.getReturnType() );
    IRType overrideRetDescriptor = getDescriptor( m.getReturnType() );

    String superParamDescriptors = getParameterDescriptors( bridge.getParameterTypes() );
    String overrideParamDescriptors = getParameterDescriptors( m.getParameterTypes() ); // e.g., foo( o: String ) -> foo( o: T )

    if( !superRetDescriptor.equals( overrideRetDescriptor ) ||
        !overrideParamDescriptors.equals( superParamDescriptors ) )
    {
      // The parameters include all type variable args and all parameters from the DFS
      List<IRSymbol> parameters = new ArrayList<IRSymbol>();
      for( IJavaClassInfo param : bridge.getParameterTypes() )
      {
        parameters.add( new IRSymbol( param.getName(), getDescriptor( param ), false ) );
      }

      setUpFunctionContext( true, parameters );

      // The body of the method is just a call through to the method on this object
      List<IRExpression> methodCallArgs = new ArrayList<IRExpression>();
      IJavaClassInfo[] params = m.getParameterTypes();
      for( int i = 0; i < params.length; i++ )
      {
        // The dfs and superDfs have to have the same number of args.  So we go through and pull out the parameter based on
        // the superDfs name, and cast it to the type of the dfs arg if it's not already compatible
        IJavaClassInfo param = params[i];
        IJavaClassInfo superParam = bridge.getParameterTypes()[i];
        IRExpression arg = identifier( _context.getSymbol( superParam.getName() ) );
        IRType expectedType = getDescriptor( param );
        if( !expectedType.isAssignableFrom( arg.getType() ) )
        {
          arg = buildCast( expectedType, arg );
        }
        methodCallArgs.add( arg );
      }
      IRMethod irMethod = IRMethodFactory.createIRMethod( _gsClass, m.getName(),
                                                          getDescriptor( m.getReturnType() ), getIRTypes( m.getParameterTypes() ),
                                                          IRelativeTypeInfo.Accessibility.fromModifiers( m.getModifiers() ), false );
      IRExpression methodCall = callMethod( irMethod, pushThis(), methodCallArgs );
      IRStatement methodBody = new IRReturnStatement( null, methodCall );
      IRMethodStatement bridgeMethod = new IRMethodStatement(
        methodBody,
        bridge.getName(),
        makeModifiersForBridgeMethod( m.getModifiers() ),
        superRetDescriptor,
        parameters );
      _irClass.addMethod( bridgeMethod );
    }
  }

  /**
   * ##hack:
   * Potentially generates a bridge method for an overridden method where the super method is in a proxy
   * and the proxy is for a Java interface having param types that are transformed to non-bytecode types
   * in the type system.
   * E.g., A guidewire platform plugin interface may declare UserBase or the like as a parameter type,
   * which is always represented as the corresponding app derivative: UserBase -> CC's User. Essentially,
   * we need to generate a bridge method to make the otherwise unsavory covariant parameter types work
   * in method overrides.
   */
  private boolean genProxyCovariantBridgeMethod( DynamicFunctionSymbol dfs, DynamicFunctionSymbol superDfs )
  {
    IGosuClassInternal superType = (IGosuClassInternal)superDfs.getScriptPart().getContainingType();
    if( superType.isProxy() )
    {
      IJavaType javaType = superType.getJavaType();
      javaType = (IJavaType)TypeLord.getDefaultParameterizedType( javaType );
      IType[] dfsArgTypes = dfs.getArgTypes();
      IType[] defDfsArgTypes = new IType[dfsArgTypes.length];
      for( int i = 0; i < dfsArgTypes.length; i++ )
      {
        defDfsArgTypes[i] = TypeLord.getDefaultParameterizedTypeWithTypeVars( dfsArgTypes[i] );
      }
      IJavaMethodInfo mi = (IJavaMethodInfo)((IRelativeTypeInfo)javaType.getTypeInfo()).getMethod( javaType, NameResolver.getFunctionName( dfs ), defDfsArgTypes );
      if( mi == null )
      {
        // Probably a generic method; the caller will gen bridge method for this
        return false;
      }
      IJavaClassMethod method = mi.getMethod();
      IJavaClassInfo[] paramClasses = method.getParameterTypes();
      for( int i = 0; i < paramClasses.length; i++ )
      {
        if( !AbstractElementTransformer.isBytecodeType( defDfsArgTypes[i] ) )
        {
          String dfsParamClass = getDescriptor( defDfsArgTypes[i] ).getName().replace( '$', '.' );
          if( !dfsParamClass.equals( paramClasses[i].getName().replace( '$', '.' ) ) )
          {
            makeCovariantParamBridgeMethod( dfs, superDfs, method );
            return true;
          }
        }
      }
      if( !AbstractElementTransformer.isBytecodeType( superDfs.getReturnType() ) )
      {
        String returnClassName = method.getReturnTypeName();
        String superReturnClassName = getDescriptor( superDfs.getReturnType() ).getName();
        if( !returnClassName.equals( superReturnClassName ) )
        {
          makeCovariantParamBridgeMethod( dfs, superDfs, method );
          return true;
        }
      }
    }
    return false;
  }

  private void makeCovariantParamBridgeMethod( DynamicFunctionSymbol dfs, DynamicFunctionSymbol superDfs, IJavaClassMethod method )
  {
    IJavaClassInfo[] paramTypes = method.getParameterTypes();
    IType[] chainedMethodParams = getParamsIncludingTypeParams( dfs );

    List<IRSymbol> parameters = new ArrayList<IRSymbol>();
    for( int i = 0; i < paramTypes.length; i++ )
    {
      parameters.add( new IRSymbol( "arg" + i, getDescriptor( paramTypes[i] ), false ) );
    }

    setUpFunctionContext( superDfs, true, parameters );

    List<IRExpression> args = new ArrayList<IRExpression>();
    for( int i = 0; i < parameters.size(); i++ )
    {
      IRExpression arg = identifier( parameters.get( i ) );
      // If the parameter on the method we're calling through to is not assignable from the type
      // of the parameter to this method, then we need to wrap the identifier in a check cast.  Presumably
      // this is true for at least one of the parameters to the method, but not for all of them
      IRType chainedParamType = getDescriptor( chainedMethodParams[i] );
      if( !chainedParamType.isAssignableFrom( arg.getType() ) )
      {
        arg = buildCast( chainedParamType, arg );
      }
      args.add( arg );
    }

    IRMethod irMethod = IRMethodFactory.createIRMethod( _gsClass, NameResolver.getFunctionName( dfs ), dfs.getReturnType(), chainedMethodParams, AccessibilityUtil.forSymbol( dfs ), false );
    IRExpression methodCall = callMethod( irMethod, pushThis(), args );

    IRStatementList methodBody = new IRStatementList( true );
    if( !method.getReturnType().getName().equals( Void.TYPE.getName() ) )
    {
      methodBody.addStatement( new IRReturnStatement( null, methodCall ) );
    }
    else
    {
      methodBody.addStatement( buildMethodCall( methodCall ) );
      methodBody.addStatement( buildReturn() );
    }

    IRMethodStatement bridgeMethod = new IRMethodStatement(
      methodBody,
      method.getName(),
      makeModifiersForBridgeMethod( getModifiers( dfs ) ),
      getDescriptor( method.getReturnClassInfo() ),
      parameters
    );
    _irClass.addMethod( bridgeMethod );
  }

  private IType[] getParamsIncludingTypeParams( DynamicFunctionSymbol dfs )
  {
    List<IGenericTypeVariable> typeVars = getTypeVarsForDFS( dfs );
    final IType[] argTypes = dfs.getArgTypes();
    IType[] paramTypes = new IType[typeVars.size() + argTypes.length];
    System.arraycopy( argTypes, 0, paramTypes, typeVars.size(), argTypes.length );
    for( int i = 0; i < typeVars.size(); i++ )
    {
      paramTypes[i] = JavaTypes.ITYPE();
    }
    return paramTypes;
  }

  private void maybePassTypeParams( DynamicFunctionSymbol dfs, List<IRExpression> args )
  {
    IType type = dfs.getType();
    if( type.isGenericType() )
    {
      IGenericTypeVariable[] typeVars = type.getGenericTypeVariables();
      for( int i = 0; i < typeVars.length; i++ )
      {
        args.add( identifier( _context.getSymbol( getTypeVarParamName( typeVars[i] ) ) ) );
      }
    }
  }

  private void compileOuterAccessMethod()
  {
    int iModifiers = Opcodes.ACC_STATIC + Opcodes.ACC_SYNTHETIC;
    iModifiers |= (BytecodeOptions.isSingleServingLoader() ? Opcodes.ACC_PUBLIC : 0);

    IRSymbol staticThis = new IRSymbol( "staticThis", getDescriptor( _gsClass ), false );

    setUpFunctionContext( false, Collections.singletonList( staticThis ) );

    IRStatement body = new IRReturnStatement( null, getInstanceField( _gsClass, getOuterThisFieldName(), getDescriptor( getRuntimeEnclosingType( _gsClass ) ), AccessibilityUtil.forOuter(),
                                                                      identifier( staticThis ) ) );

    _irClass.addMethod( new IRMethodStatement(
      body,
      OUTER_ACCESS,
      iModifiers,
      getDescriptor( getRuntimeEnclosingType( _gsClass ) ),
      Collections.singletonList( staticThis )
    ) );
  }

  private void compileMethod( DynamicFunctionSymbol dfs )
  {
    if( isGosuObjectMethod( dfs ) ||
        isStaticEnumMethod( dfs ) )
    {
      return;
    }

    if( !(dfs instanceof TemplateRenderFunctionSymbol) &&
        getGosuClass() instanceof IGosuTemplateInternal )
    {
      // Only compile renderXxx() methods on templates
      return;
    }

    List<IRSymbol> parameters = new ArrayList<IRSymbol>();

    maybeAddImplicitEnhancementParameters( dfs, parameters );
    maybeGetTypeVarSymbolTypes( dfs, parameters );
    maybeAddImplicitExternalSymbolsParameter( dfs, parameters );
    for( ISymbol param : dfs.getArgs() )
    {
      String name = param.getName();
      if( isBlockInvoke( dfs ) )
      {
        name = name + "$$blockParam";
      }
      else if( param.isValueBoxed() )
      {
        name = name + "$$unboxedParam";
      }

      if( param.getName().equals( "p0" ) && param.getType().equals( JavaTypes.IEXTERNAL_SYMBOL_MAP() ) )
      {
        name = GosuFragmentTransformer.SYMBOLS_PARAM_NAME;
      }

      parameters.add( new IRSymbol( name, getDescriptor( param.getType() ), false ) );
    }

    IRStatement methodBody;
    if( !dfs.isAbstract() )
    {
      IStatement stmt = (IStatement)dfs.getValueDirectly();
      if( stmt != null )
      {
        setUpFunctionContext( dfs, !dfs.isStatic() && !isCompilingEnhancement(), parameters );
        FunctionStatementTransformer funcStmtCompiler = new FunctionStatementTransformer( dfs, _context );
        methodBody = funcStmtCompiler.compile();
      }
      else
      {
        methodBody = new IRNoOpStatement();
//        Label label = new Label();
//        _mv.visitLabel( label );
//        _mv.visitLineNumber( dfs.getDeclFunctionStmt().getLineNum(), label );
//        _mv.visitMaxs( 0, 0 );
      }
    }
    else
    {
      methodBody = null;
    }

    IRMethodStatement method = new IRMethodStatement( methodBody,
                                                      NameResolver.getFunctionName( dfs ),
                                                      getModifiers( dfs ),
                                                      getDescriptor( dfs.getReturnType() ),
                                                      parameters );
    method.setAnnotations( getIRAnnotations( makeAnnotationInfos( dfs.getModifierInfo().getAnnotations(), getGosuClass().getTypeInfo() ) ) );
    _irClass.addMethod( method );
  }

  private boolean isStaticEnumMethod( DynamicFunctionSymbol dfs )
  {
    return getGosuClass().isEnum() && (dfs instanceof EnumValueOfFunctionSymbol ||
                                       dfs instanceof EnumValuesFunctionSymbol);
  }

  private boolean isGosuObjectMethod( DynamicFunctionSymbol dfs )
  {
    return dfs.getScriptPart() != null && dfs.getScriptPart().getContainingType() == getGosuObjectInterface();
  }

  private IGosuClassInternal getGosuObjectInterface()
  {
    return IGosuClassInternal.Util.getGosuClassFrom( JavaTypes.IGOSU_OBJECT() );
  }

  private void compileIntrinsicTypePropertyGetter()
  {
    setUpFunctionContext( true, Collections.<IRSymbol>emptyList() );
    //## todo: Cache the type...

    IRExpression getTypeExpression = callStaticMethod( GosuRuntimeMethods.class, "getType", new Class[]{Object.class},
                                                       Collections.singletonList( pushThis() ) );
    if( _gsClass.isGenericType() )
    {
      getTypeExpression = callMethod( IType.class, "getParameterizedType", new Class[]{IType[].class},
                                      getTypeExpression,
                                      Collections.singletonList( makeArrayOfTypeParameters() ) );
    }

    IRStatement methodBody = new IRReturnStatement( null, getTypeExpression );
    IRMethodStatement method = new IRMethodStatement(
      methodBody,
      "getIntrinsicType",
      // Note this is synthetic as a hacky way to hide property from tools (e.g. hibernate)
      Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
      getDescriptor( IType.class ),
      Collections.<IRSymbol>emptyList()
    );
    _irClass.addMethod( method );
  }

  private void compileMainMethod()
  {
    if( !(getGosuClass() instanceof IGosuProgram) )
    {
      return;
    }

    //
    // Simply constructs an instance of the program and then invokes the evaluate() method.
    //

    List<IType> paramTypes = new ArrayList<IType>();
    IMethodInfo evaluateMethod = null;
    for( IMethodInfo mi : getGosuClass().getTypeInfo().getMethods() )
    {
      if( mi.getName().startsWith( "evaluate(" ) )
      {
        evaluateMethod = mi;
        for( IParameterInfo param : mi.getParameters() )
        {
          IType paramType = param.getFeatureType();
          paramTypes.add( paramType );
        }
        break;
      }
    }
    IRExpression newProgram = buildNewExpression( IRTypeResolver.getDescriptor( getGosuClass() ), Collections.<IRType>emptyList(), Collections.<IRExpression>emptyList() );
    IRMethod evaluateIRMethod = IRMethodFactory.createIRMethod( getGosuClass(), "evaluate", evaluateMethod.getReturnType(), paramTypes.toArray( new IType[paramTypes.size()] ), IRelativeTypeInfo.Accessibility.PUBLIC, false );
    IRExpression callEvaluate = callMethod( evaluateIRMethod, newProgram, Collections.singletonList( nullLiteral() ) );
    IRStatement methodBody = new IRStatementList( true, buildMethodCall( callEvaluate ), buildReturn() );
    IRMethodStatement method = new IRMethodStatement(
      methodBody,
      "main",
      Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
      getDescriptor( void.class ),
      Collections.singletonList( new IRSymbol( "args", getDescriptor( String[].class ), false ) )
    );
    _irClass.addMethod( method );
  }

  private void compileAnnotationGetter()
  {
    if( !getGosuClass().isInterface() )
    {
      addEvalAnnotationMethod( _irClass );
    }
  }

  private void addEvalAnnotationMethod( IRClass irClass )
  {
    List<StatementList> annotationInitMethods = getGosuClass().getAnnotationInitialization();
    IRType annMapIRClass = JavaClassIRType.get( AnnotationMap.class );
    IRSymbol builderSymbol = new IRSymbol( AnnotationBuilder.BUILDER_SYMBOL.get().getName(), annMapIRClass, false );
    for( int i = 0; i < annotationInitMethods.size(); i++ )
    {
      StatementList initMethod = annotationInitMethods.get( i );
      List<IRSymbol> args = new ArrayList<IRSymbol>();
      String ending = "";
      if( i != 0 )
      {
        ending = "" + i;
        args.add( builderSymbol );
      }
      setUpFunctionContext( false, args );
      IRStatementList statement = (IRStatementList)StatementTransformer.compile( _context, initMethod );
      if( i < annotationInitMethods.size() - 1 )
      {
        IRMethod method = IRMethodFactory.createIRMethod( getGosuClass(),
                                                          GosuClass.EVAL_ANNOTATIONS_METHOD + (i + 1),
                                                          JavaClassIRType.get( Map.class ),
                                                          Arrays.asList( annMapIRClass ),
                                                          IRelativeTypeInfo.Accessibility.PUBLIC,
                                                          true );
        statement.addStatement( new IRReturnStatement( null, callMethod( method, null, exprList( identifier( builderSymbol ) ) ) ) );
      }
      IRMethodStatement method = new IRMethodStatement(
        statement,
        GosuClass.EVAL_ANNOTATIONS_METHOD + ending, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, getDescriptor( Map.class ),
        args );
      irClass.addMethod( method );
    }
  }

  private void compileEnumValuesMethod()
  {
    setUpFunctionContext( true, Collections.<IRSymbol>emptyList() );

    int numberOfEnumFields = 0;
    for( IVarStatement varStatement : _gsClass.getStaticFields() )
    {
      if( varStatement.isEnumConstant() )
      {
        numberOfEnumFields++;
      }
    }

    // We want to generate approximately the following code:
    // Foo[] temp = new Foo[numberOfEnumFields];
    // System.arrayCopy(ENUM_VALUES_FIELD, 0, temp, 0, numberOfEnumFields);
    // return temp;

    List<IRStatement> bodyStatements = new ArrayList<IRStatement>();
    IRSymbol tempSymbol = _context.makeAndIndexTempSymbol( getDescriptor( _gsClass.getArrayType() ) );
    IRStatement arrayCreation = buildAssignment( tempSymbol, newArray( getDescriptor( _gsClass ), numericLiteral( numberOfEnumFields ) ) );
    bodyStatements.add( arrayCreation );
    IRExpression arrayCopy = callStaticMethod( System.class, "arraycopy", new Class[]{Object.class, int.class, Object.class, int.class, int.class},
                                               exprList(
                                                 getStaticField( _gsClass, ENUM_VALUES_FIELD, getDescriptor( _gsClass.getArrayType() ), IRelativeTypeInfo.Accessibility.PUBLIC ),
                                                 numericLiteral( 0 ),
                                                 identifier( tempSymbol ),
                                                 numericLiteral( 0 ),
                                                 numericLiteral( numberOfEnumFields )
                                               ) );
    bodyStatements.add( buildMethodCall( arrayCopy ) );
    bodyStatements.add( new IRReturnStatement( null, identifier( tempSymbol ) ) );

    IRMethodStatement method = new IRMethodStatement(
      new IRStatementList( true, bodyStatements ),
      "values",
      Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
      getDescriptor( _gsClass.getArrayType() ),
      Collections.<IRSymbol>emptyList()
    );
    _irClass.addMethod( method );
  }

  private void compileEnumAllValuesPropertyGetter()
  {
    setUpFunctionContext( true, Collections.<IRSymbol>emptyList() );
    IRMethod valuesMethod = IRMethodFactory.createIRMethod( _gsClass, "values", _gsClass.getArrayType(), new IType[0], IRelativeTypeInfo.Accessibility.PUBLIC, true );
    IRExpression result = callStaticMethod( Arrays.class, "asList", new Class[]{Object[].class},
                                            exprList( callMethod( valuesMethod, null, exprList() ) ) );

    IRMethodStatement method = new IRMethodStatement(
      new IRStatementList( true, new IRReturnStatement( null, result ) ),
      "getAllValues",
      Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
      getDescriptor( List.class ),
      Collections.<IRSymbol>emptyList()
    );
    _irClass.addMethod( method );
  }

  private void compileEnumValueOfMethod()
  {
    IRSymbol argSymbol = new IRSymbol( "arg", IRTypeConstants.STRING, false );
    setUpFunctionContext( true, Collections.singletonList( argSymbol ) );

    IRExpression result = callStaticMethod( Enum.class, "valueOf", new Class[]{Class.class, String.class},
                                            exprList( classLiteral( getDescriptor( _gsClass ) ), identifier( argSymbol ) ) );
    IRMethodStatement method = new IRMethodStatement(
      new IRStatementList( true, new IRReturnStatement( null, checkCast( _gsClass, result ) ) ),
      "valueOf",
      Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
      getDescriptor( _gsClass ),
      Collections.singletonList( argSymbol )
    );
    _irClass.addMethod( method );
  }

  private void compileEnumValuePropertyGetter()
  {
    setUpFunctionContext( true, Collections.<IRSymbol>emptyList() );

    IRMethodStatement method = new IRMethodStatement(
      new IRStatementList( true, new IRReturnStatement( null, pushThis() ) ),
      "getValue",
      Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
      IRTypeConstants.OBJECT,
      Collections.<IRSymbol>emptyList()
    );
    _irClass.addMethod( method );
  }

  private void compileEnumCodePropertyGetter()
  {
    // Don't bother if the class already has the property defined
    IDynamicPropertySymbol existingProperty = getGosuClass().getMemberProperty( CaseInsensitiveCharSequence.get( "Code" ) );
    if( existingProperty != null && !(existingProperty instanceof EnumCodePropertySymbol) )
    {
      return;
    }

    setUpFunctionContext( true, Collections.<IRSymbol>emptyList() );

    IRExpression returnValue = callMethod( Enum.class, "name", new Class[0], pushThis(), exprList() );
    IRMethodStatement method = new IRMethodStatement(
      new IRStatementList( true, new IRReturnStatement( null, returnValue ) ),
      "getCode",
      Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
      IRTypeConstants.STRING,
      Collections.<IRSymbol>emptyList()
    );
    _irClass.addMethod( method );
  }

  private void compileEnumOrdinalPropertyGetter()
  {
    setUpFunctionContext( true, Collections.<IRSymbol>emptyList() );

    IRExpression returnValue = callMethod( Enum.class, "ordinal", new Class[0], pushThis(), exprList() );
    IRMethodStatement method = new IRMethodStatement(
      new IRStatementList( true, new IRReturnStatement( null, returnValue ) ),
      "getOrdinal",
      Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
      IRTypeConstants.pINT,
      Collections.<IRSymbol>emptyList()
    );
    _irClass.addMethod( method );
  }

  private void compileEnumNamePropertyGetter()
  {
    // Don't bother if the class already has the property defined
    IDynamicPropertySymbol existingProperty = getGosuClass().getMemberProperty( CaseInsensitiveCharSequence.get( "Name" ) );
    if( existingProperty != null && !(existingProperty instanceof EnumNamePropertySymbol) )
    {
      return;
    }

    setUpFunctionContext( true, Collections.<IRSymbol>emptyList() );

    IRExpression returnValue = callMethod( Enum.class, "name", new Class[0], pushThis(), exprList() );
    IRMethodStatement method = new IRMethodStatement(
      new IRStatementList( true, new IRReturnStatement( null, returnValue ) ),
      "getName",
      Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
      IRTypeConstants.STRING,
      Collections.<IRSymbol>emptyList()
    );
    _irClass.addMethod( method );
  }

  private void compileEnumDisplayNamePropertyGetter()
  {
    // Don't bother if the class already has the property defined
    IDynamicPropertySymbol existingProperty = getGosuClass().getMemberProperty( CaseInsensitiveCharSequence.get( "DisplayName" ) );
    if( existingProperty != null && !(existingProperty instanceof EnumDisplayNamePropertySymbol) )
    {
      return;
    }

    setUpFunctionContext( true, Collections.<IRSymbol>emptyList() );

    IRExpression returnValue = callMethod( Enum.class, "toString", new Class[0], pushThis(), exprList() );
    IRMethodStatement method = new IRMethodStatement(
      new IRStatementList( true, new IRReturnStatement( null, returnValue ) ),
      "getDisplayName",
      Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
      IRTypeConstants.STRING,
      Collections.<IRSymbol>emptyList()
    );
    _irClass.addMethod( method );
  }

  private IRExpression makeArrayOfTypeParameters()
  {
    IGenericTypeVariable[] genTypeVars = _gsClass.getGenericTypeVariables();

    List<IRExpression> values = new ArrayList<IRExpression>();
    for( IGenericTypeVariable gv : genTypeVars )
    {
      values.add( getInstanceField( _gsClass, TYPE_PARAM_PREFIX + gv.getName(), IRTypeConstants.ITYPE, AccessibilityUtil.forTypeParameter(), pushThis() ) );
    }

    return buildInitializedArray( IRTypeConstants.ITYPE,
                                  values );
  }

  public void initializeInstanceFields( List<IRStatement> statements )
  {
    List<IVarStatement> fields = getOrderedFields();
    for( IVarStatement field : fields )
    {
      statements.add( FieldInitializerTransformer.compile( _context, field ) );
    }
  }

  public void initTypeVarFields( List<IRStatement> statements )
  {
    IGosuClassInternal gsClass = _gsClass;

    if( gsClass.isGenericType() )
    {
      for( IGenericTypeVariable genTypeVar : gsClass.getGenericTypeVariables() )
      {
        statements.add(
          setInstanceField( gsClass, TYPE_PARAM_PREFIX + genTypeVar.getName(), IRTypeConstants.ITYPE, AccessibilityUtil.forTypeParameter(),
                            pushThis(),
                            identifier( _context.getSymbol( getTypeVarParamName( genTypeVar ) ) ) ) );
      }
    }

    while( gsClass.isAnonymous() )
    {
      IDynamicFunctionSymbol dfs = getEnclosingDFS( gsClass );
      if( dfs == null )
      {
        break;
      }
      for( IGenericTypeVariable genTypeVar : getTypeVarsForDFS( dfs ) )
      {
        statements.add(
          setInstanceField( _gsClass, TYPE_PARAM_PREFIX + genTypeVar.getName(), IRTypeConstants.ITYPE, AccessibilityUtil.forTypeParameter(),
                            pushThis(),
                            identifier( _context.getSymbol( getTypeVarParamName( genTypeVar ) ) ) ) );
      }
      gsClass = (IGosuClassInternal)dfs.getGosuClass();
    }
  }

  public void initCapturedSymbolFields( List<IRStatement> statements )
  {
    Map<CaseInsensitiveCharSequence, ICapturedSymbol> capturedSymbols = _gsClass.getCapturedSymbols();
    if( capturedSymbols != null )
    {
      @SuppressWarnings({"UnusedDeclaration"})
      int iIndex = 1; // one for 'this', and...
      iIndex += (_gsClass.isStatic() ? 0 : 1); // ...one for outer 'this' (if non-static)
      for( ICapturedSymbol sym : capturedSymbols.values() )
      {
        statements.add( setInstanceField( _gsClass, CAPTURED_VAR_PREFIX + sym.getName(), getDescriptor( sym.getType().getArrayType() ), AccessibilityUtil.forCapturedVar(),
                                          pushThis(),
                                          identifier( _context.getSymbol( getCapturedSymbolParameterName( sym ) ) ) ) );
      }
    }

    if( requiresExternalSymbolCapture( _gsClass ) )
    {
      statements.add( setInstanceField( _gsClass, GosuFragmentTransformer.SYMBOLS_PARAM_NAME, getDescriptor( IExternalSymbolMap.class ), AccessibilityUtil.forCapturedVar(),
                                        pushThis(),
                                        identifier( _context.getSymbol( GosuFragmentTransformer.SYMBOLS_PARAM_NAME + "arg" ) ) ) );
    }
  }

  public void initializeStaticFields( List<IRStatement> statements )
  {
    boolean needToCompileEnumValuesField = _gsClass.isEnum();
    List<IVarStatement> fields = _gsClass.getStaticFields();
    for( IVarStatement field : fields )
    {
      if( field.isEnumConstant() )
      {
        _enumCounter.increment( field.getIdentifierName().toString() );
      }
      else if( needToCompileEnumValuesField && !field.isEnumConstant() )
      {
        // We want to insert the values field directly after the last constant field
        compileEnumValuesFieldInitializer( statements );
        needToCompileEnumValuesField = false;
      }
      statements.add( FieldInitializerTransformer.compile( _context, field ) );
    }

    // Initialize the field here just in case the enum class had no constants
    if( needToCompileEnumValuesField )
    {
      compileEnumValuesFieldInitializer( statements );
    }
  }

  private void compileEnumValuesFieldInitializer( List<IRStatement> statements )
  {
    List<IVarStatement> enumFields = new ArrayList<IVarStatement>();
    for( IVarStatement varStatement : _gsClass.getStaticFields() )
    {
      if( varStatement.isEnumConstant() )
      {
        enumFields.add( varStatement );
      }
    }

    List<IRExpression> values = new ArrayList<IRExpression>();
    for( int i = 0; i < enumFields.size(); i++ )
    {
      values.add( getStaticField( _gsClass,
                                  enumFields.get( i ).getIdentifierName().toString(),
                                  getDescriptor( enumFields.get( i ).getType() ),
                                  IRelativeTypeInfo.Accessibility.PUBLIC ) );
    }
    IRExpression arrayBuilder = buildInitializedArray( getDescriptor( _gsClass ), values );

    statements.add( setStaticField( _gsClass, ENUM_VALUES_FIELD, getDescriptor( _gsClass.getArrayType() ), IRelativeTypeInfo.Accessibility.PUBLIC, arrayBuilder ) );
  }

  private List<IVarStatement> getOrderedFields()
  {
    //noinspection unchecked
    List<MemberFieldSymbol> fields = new ArrayList( _gsClass.getMemberFieldIndexByName().values() );
    Collections.sort( fields,
                      new Comparator<MemberFieldSymbol>()
                      {
                        public int compare( MemberFieldSymbol o1, MemberFieldSymbol o2 )
                        {
                          return o1.getIndex() - o2.getIndex();
                        }
                      } );
    List<IVarStatement> fieldStmts = new ArrayList<IVarStatement>( fields.size() );
    for( MemberFieldSymbol field : fields )
    {
      fieldStmts.add( _gsClass.getMemberField( field.getCaseInsensitiveName() ) );
    }
    return fieldStmts;
  }

  private String getParameterDescriptors( IType[] types )
  {
    StringBuilder sb = new StringBuilder();
    for( IType type : types )
    {
      sb.append( getDescriptor( type ).getName() );
    }
    return sb.toString();
  }

  private String getParameterDescriptors( IJavaClassInfo[] iJavaClassInfos )
  {
    StringBuffer sb = new StringBuffer();
    for( IJavaClassInfo type : iJavaClassInfos )
    {
      sb.append( getDescriptor( type ).getName() );
    }
    return sb.toString();
  }

  private String getParameterDescriptors( Class[] types )
  {
    StringBuilder sb = new StringBuilder();
    for( Class type : types )
    {
      sb.append( getDescriptor( type ).getName() );
    }
    return sb.toString();
  }

  private void compileClassHeader()
  {
    _irClass.setName( _gsClass.getName() );
    _irClass.setThisType( getDescriptor( _gsClass ) );
    _irClass.setModifiers( getClassModifiers() );
    _irClass.setSuperType( getSuperSlashName() );
    for( IRType iface : getInterfaceNames() )
    {
      _irClass.addInterface( iface );
    }
  }

  private IRType getSuperSlashName()
  {
    return _gsClass.getSupertype() == null
           ? getDescriptor( Object.class )
           : getDescriptor( _gsClass.getSupertype() );
  }

  private void addSourceFileRef()
  {
    String sourceFileRef = _context.getSourceFileRef();
    if( sourceFileRef != null )
    {
      _irClass.setSourceFile( sourceFileRef );
    }
  }

  private int getClassModifiers()
  {
    return getClassModifiers( _gsClass, false );
  }

  private int getClassModifiers( IGosuClass gsClass, boolean bForInnerClass )
  {
    int iModifiers = 0;
    if( gsClass == _gsClass )
    {
      if( gsClass.isInterface() )
      {
        iModifiers |= Opcodes.ACC_INTERFACE;
      }
      else
      {
        if( gsClass.isEnum() )
        {
          iModifiers |= Opcodes.ACC_ENUM | Opcodes.ACC_FINAL;
        }

        if( !bForInnerClass )
        {
          iModifiers |= Opcodes.ACC_SUPER;
        }
      }
    }
    int iGsModifiers = gsClass.getModifiers();
    if( Modifier.isPublic( iGsModifiers ) || (!Modifier.isInternal( iGsModifiers ) && !Modifier.isPrivate( iGsModifiers )) ||
        BytecodeOptions.isSingleServingLoader() )
    {
      iModifiers |= Opcodes.ACC_PUBLIC;
    }
    else if( bForInnerClass && gsClass.getEnclosingType() != null && Modifier.isPrivate( iGsModifiers ) && !BytecodeOptions.isSingleServingLoader() )
    {
      iModifiers |= Opcodes.ACC_PRIVATE;
    }
    if( Modifier.isFinal( iGsModifiers ) )
    {
      iModifiers |= Opcodes.ACC_FINAL;
    }
    if( Modifier.isAbstract( iGsModifiers ) || gsClass.isInterface() )
    {
      iModifiers |= Opcodes.ACC_ABSTRACT;
    }
    if( bForInnerClass &&
        (Modifier.isStatic( iGsModifiers ) ||
         (gsClass.getEnclosingType() != null && gsClass.isInterface())) )
    {
      iModifiers |= Opcodes.ACC_STATIC;
    }
    return iModifiers;
  }

  private IRType[] getInterfaceNames()
  {
    IType[] interfaces = _gsClass.getInterfaces();
    if( interfaces == null || interfaces.length == 0 )
    {
      return new IRType[0];
    }

    List<IRType> ifaceNames = new ArrayList<IRType>();
    for( IType iface : interfaces )
    {
      if( iface == getGosuObjectInterface() )
      {
        iface = JavaTypes.IGOSU_CLASS_OBJECT();
      }

      IRType irInterface = getDescriptor( iface );
      if( !ifaceNames.contains( irInterface ) )
      {
        ifaceNames.add( irInterface );
      }
    }
    return ifaceNames.toArray( new IRType[ifaceNames.size()] );
  }

  public IGosuEnhancementInternal getGosuEnhancement()
  {
    return (IGosuEnhancementInternal)_gsClass;
  }

  public void pushEnumNameAndOrdinal( IType type, List<IRExpression> args )
  {
    if( type.isEnum() )
    {
      args.add( pushConstant( _enumCounter.getNextEnumName() ) );
      args.add( pushConstant( _enumCounter.getNextEnumOrdinal() ) );
    }
  }

  //
//  public void addCtxMethod( List<IType> args )
//  {
//    _ctxMethods.add( args );
//  }
//
  static final class EnumOrdinalCounter
  {
    private int _nextEnumOrdinal = -1;
    private String _nextEnumName;

    public int getNextEnumOrdinal()
    {
      return _nextEnumOrdinal;
    }

    public String getNextEnumName()
    {
      return _nextEnumName;
    }

    private void increment( String name )
    {
      _nextEnumOrdinal++;
      _nextEnumName = name;
    }
  }

  // ------------------------------------------- Additions

  public boolean isBlockInvoke( DynamicFunctionSymbol dfs )
  {
    return dfs.getDisplayName().equals( BlockClass.INVOKE_METHOD_NAME ) && _context.compilingBlock();
  }

  private void setUpFunctionContext( boolean instanceMethod, List<IRSymbol> params )
  {
    _context.initBodyContext( !instanceMethod );
    _context.pushScope( instanceMethod );
    _context.putSymbols( params );
  }

  private void setUpFunctionContext( DynamicFunctionSymbol dfs, boolean instanceMethod, List<IRSymbol> params )
  {
    _context.initBodyContext( dfs.isStatic(), dfs );
    _context.pushScope( instanceMethod );
    _context.putSymbols( params );
  }

  @Override
  public String toString()
  {
    return "Transforming Class: " + getGosuClass().getName();
  }
}
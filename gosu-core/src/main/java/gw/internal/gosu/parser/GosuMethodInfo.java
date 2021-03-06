/*
 * Copyright 2012. Guidewire Software, Inc.
 */

package gw.internal.gosu.parser;

import gw.lang.parser.IExpression;
import gw.lang.parser.IReducedSymbol;
import gw.lang.parser.TypeVarToTypeMap;
import gw.lang.reflect.java.JavaTypes;
import gw.util.GosuStringUtil;
import gw.util.GosuExceptionUtil;
import gw.lang.parser.exceptions.ErrantGosuClassException;
import gw.lang.reflect.gs.IGenericTypeVariable;
import gw.lang.reflect.*;
import gw.lang.reflect.IAnnotationInfo;
import gw.lang.reflect.IFeatureInfo;
import gw.lang.reflect.IMethodCallHandler;
import gw.lang.reflect.IMethodInfo;
import gw.lang.reflect.IType;
import gw.lang.reflect.gs.IGosuMethodInfo;
import gw.lang.reflect.gs.IGosuProgram;
import gw.internal.gosu.ir.transform.util.NameResolver;
import gw.internal.gosu.ir.transform.AbstractElementTransformer;
import gw.internal.gosu.ir.nodes.IRMethodFactory;
import gw.internal.gosu.ir.nodes.IRMethodFromMethodInfo;
import gw.lang.ir.IRType;

import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;

/**
 */
public class GosuMethodInfo extends AbstractGenericMethodInfo implements IGosuMethodInfo
{
  private IType _returnType;
  private IMethodCallHandler _callHandler;

  public GosuMethodInfo( IFeatureInfo container, DynamicFunctionSymbol dfs )
  {
    super( container, dfs );
  }

  public IType getReturnType()
  {
    if( _returnType == null )
    {
      IType rawReturnType = ((FunctionType)getDfs().getType()).getReturnType();
      IType ownerType = getOwnersType();
      if( ownerType.isParameterizedType() &&
          //## Since DFSs are parameterized now, I'm not sure we ever need to get the actual type here (or in the params)
          !(getDfs() instanceof ReducedParameterizedDynamicFunctionSymbol) )
      {
        TypeVarToTypeMap actualParamByVarName = TypeLord.mapTypeByVarName( ownerType, ownerType, true );
        for( IGenericTypeVariable tv : getTypeVariables() )
        {
          if( actualParamByVarName.isEmpty() )
          {
            actualParamByVarName = new TypeVarToTypeMap();
          }
          actualParamByVarName.put( tv.getTypeVariableDefinition().getType(), tv.getTypeVariableDefinition().getType() );
        }
        _returnType = TypeLord.getActualType( rawReturnType, actualParamByVarName, true );
      }
      else
      {
        _returnType = rawReturnType;
      }
    }
    if (TypeSystem.isDeleted(_returnType)) {
      _returnType =TypeSystem.getErrorType();
    }
    if( _returnType.isGenericType() && !_returnType.isParameterizedType() )
    {
      _returnType = TypeLord.getDefaultParameterizedType( _returnType );
    }
    return _returnType;
  }

  public IMethodCallHandler getCallHandler()
  {
    if( _callHandler == null )
    {
      IGosuClassInternal gsClass = getGosuClass();
      if( !gsClass.isValid() )
      {
        throw new ErrantGosuClassException( gsClass );
      }
      _callHandler = new GosuMethodCallHandler();
    }

    return _callHandler;
  }

  public String getReturnDescription()
  {
    List<IAnnotationInfo> annotation = getAnnotationsOfType(JavaTypes.getGosuType(gw.lang.Returns.class));
    if( annotation.isEmpty() )
    {
      return "";
    }
    else
    {
      String value = (String) annotation.get( 0 ).getFieldValue("value");
      return value == null ? "" : value;
    }
  }

  @Override
  public IExpression[] getDefaultValueExpressions()
  {
    List<IExpression> defValues = new ArrayList<IExpression>();
    for( IReducedSymbol s : getArgs() )
    {
      IExpression defValue = s.getDefaultValueExpression();
      defValues.add( defValue );
    }
    return defValues.toArray( new IExpression[defValues.size()] );
  }

  @Override
  public String[] getParameterNames()
  {
    List<String> names = new ArrayList<String>();
    for( IReducedSymbol a : getArgs() )
    {
      names.add( a.getName() );
    }
    return names.toArray( new String[names.size()] );
  }

  public boolean equals( Object o )
  {
    if( this == o )
    {
      return true;
    }
    if( o == null || getClass() != o.getClass() )
    {
      return false;
    }
    GosuMethodInfo that = (GosuMethodInfo)o;
    return getName().equals(that.getName());
  }

  public int hashCode()
  {
    return getName().hashCode();
  }

  public int compareTo( Object o )
  {
    return getName().compareTo(((IMethodInfo) o).getName());
  }

  public boolean isMethodForProperty() {
    return getName().startsWith("@");
  }

  @Override
  public IGosuMethodInfo getBackingMethodInfo() {
    return this;
  }

  @Override
  public String toString() {
    return getName();
  }

  public static Method getMethod( Class<?> clazz, String strName, Class[] argClasses )
  {
    if( strName.startsWith( "@" ) )
    {
      strName = argClasses.length == 1
                ? "set" + strName.substring( 1 )
                : "get" + strName.substring( 1 );
    }

    Method method = AbstractElementTransformer.getDeclaredMethod( clazz, strName, argClasses );
    if( method == null )
    {
      throw new IllegalStateException( "Could not find method " + strName + "(" + GosuStringUtil.join( ",", argClasses ) + ") on " + clazz );
    }
    method.setAccessible( true );
    return method;
  }

  protected List<IGosuAnnotation> getGosuAnnotations()
  {
    // PL-21981: if annotation is coming from the interface CCE is thrown
//    ReducedDynamicFunctionSymbol dfs = getDfs();
//    if( dfs instanceof ReducedDelegateFunctionSymbol )
//    {
//      IMethodInfo miTarget = ((ReducedDelegateFunctionSymbol)dfs).getTargetMethodInfo();
//      if( miTarget != this && miTarget instanceof AbstractGenericMethodInfo )
//      {
//        return ((AbstractGenericMethodInfo)miTarget).getGosuAnnotations();
//      }
//    }
    return super.getGosuAnnotations();
  }

  //----------------------------------------------------------------------------
  // -- private methods --

  private class GosuMethodCallHandler implements IMethodCallHandler
  {
    public Object handleCall( Object gsClassInstance, Object... args )
    {
      ReducedDynamicFunctionSymbol dfs = getDfs();
      try
      {
        boolean isEnhancement = AbstractElementTransformer.requiresImplicitEnhancementArg( dfs );
        IGosuClassInternal dfsClass = dfs.getGosuClass();

        // If this is an enhancement method or requires method type variable arguments
        // do the dirty work of extracting the appropriate argumetns
        if( isEnhancement || dfs.hasTypeVariables() || dfs.getGosuClass() instanceof IGosuProgram )
        {
          List<Object> argList = new ArrayList<Object>();

          //Handle enhancement args
          if( isEnhancement )
          {
            argList.add( gsClassInstance );
            if( !dfs.isStatic() )
            {
              if( dfsClass.isParameterizedType() )
              {
                argList.addAll( Arrays.asList( dfsClass.getTypeParameters() ) );
              }
              else
              {
                for( int i = 0; i < dfsClass.getGenericTypeVariables().length; i++ )
                {
                  IGenericTypeVariable tv = dfsClass.getGenericTypeVariables()[i];
                  argList.add( tv.getBoundingType() );
                }
              }
            }
          }

          //handle function args
          for( IGenericTypeVariable typeVar : dfs.getType().getGenericTypeVariables() )
          {
            argList.add( typeVar.getBoundingType() );
          }

          // If it's an instance of a Gosu program, for now pass through null for the external symbols argument
//            if ( dfs.getGosuClass() instanceof IGosuProgram ) {
//              argList.add( null );
//            }

          if ( args != null ) {
            argList.addAll( Arrays.asList( args ) );
          }
          args = argList.toArray();
        }
        Class clazz = dfsClass.getBackingClass();
        IRMethodFromMethodInfo irMethod = IRMethodFactory.createIRMethod(GosuMethodInfo.this, (IFunctionType) dfs.getType());
        List<IRType> allParameterTypes = irMethod.getAllParameterTypes();
        Class[] paramClasses = new Class[allParameterTypes.size()];
        for (int i = 0; i < allParameterTypes.size(); i++) {
          paramClasses[i] = allParameterTypes.get(i).getJavaClass();
        }
        Method method = getMethod( clazz, NameResolver.getFunctionName( dfs ), paramClasses );
        return method.invoke( gsClassInstance, args );
      }
      catch( IllegalAccessException e )
      {
        throw GosuExceptionUtil.forceThrow( e );
      }
      catch( InvocationTargetException e )
      {
        throw GosuExceptionUtil.forceThrow( e.getTargetException() );
      }
    }
  }
}

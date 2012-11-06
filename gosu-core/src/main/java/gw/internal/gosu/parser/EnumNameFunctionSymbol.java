/*
 * Copyright 2012. Guidewire Software, Inc.
 */

package gw.internal.gosu.parser;

import gw.internal.gosu.parser.statements.SyntheticFunctionStatement;
import gw.lang.reflect.FunctionType;
import gw.lang.reflect.gs.IGosuClass;
import gw.lang.parser.ISymbol;
import gw.lang.parser.ISymbolTable;
import gw.lang.parser.ScriptPartId;
import gw.lang.reflect.java.JavaTypes;

import java.util.Collections;


/**
 */
public class EnumNameFunctionSymbol extends DynamicFunctionSymbol
{
  public EnumNameFunctionSymbol( IGosuClassInternal gsClass, ISymbolTable symTable )
  {
    super( symTable, "@Name",
           new FunctionType( "@Name", JavaTypes.STRING(), null ),
           Collections.<ISymbol>emptyList(), new SyntheticFunctionStatement() );
    SyntheticFunctionStatement stmt = (SyntheticFunctionStatement)getValueDirectly();
    stmt.setDfsOwner( this );
    _scriptPartId = new ScriptPartId( gsClass, null );
    setStatic( false );
  }

  public DynamicFunctionSymbol getParameterizedVersion( IGosuClass gsClass )
  {
    return this;
  }
}
/*
 * Copyright 2012. Guidewire Software, Inc.
 */

package gw.lang.parser;

public interface ICapturedSymbol extends IFunctionSymbol
{
  ISymbol getReferredSymbol();
}

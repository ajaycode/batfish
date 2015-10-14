package org.batfish.question;

import org.batfish.common.BatfishException;
import org.batfish.representation.Interface;
import org.batfish.representation.Prefix;

public enum InterfacePrefixExpr implements PrefixExpr {
   INTERFACE_PREFIX;

   @Override
   public Prefix evaluate(Environment environment) {
      Interface iface = environment.getInterface();
      switch (this) {

      case INTERFACE_PREFIX:
         return iface.getPrefix();

      default:
         throw new BatfishException("invalid interface prefix expr");
      }

   }

   @Override
   public String print(Environment environment) {
      return evaluate(environment).toString();
   }

}

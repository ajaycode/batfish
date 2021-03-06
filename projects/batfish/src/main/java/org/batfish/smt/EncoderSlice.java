package org.batfish.smt;

import com.microsoft.z3.ArithExpr;
import com.microsoft.z3.BitVecExpr;
import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Context;
import com.microsoft.z3.Expr;
import com.microsoft.z3.Solver;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

import javax.annotation.Nullable;
import org.batfish.common.BatfishException;
import org.batfish.common.plugin.IBatfish;
import org.batfish.datamodel.BgpNeighbor;
import org.batfish.datamodel.CommunityList;
import org.batfish.datamodel.CommunityListLine;
import org.batfish.datamodel.Configuration;
import org.batfish.datamodel.HeaderSpace;
import org.batfish.datamodel.Interface;
import org.batfish.datamodel.IpAccessList;
import org.batfish.datamodel.IpAccessListLine;
import org.batfish.datamodel.IpProtocol;
import org.batfish.datamodel.IpWildcard;
import org.batfish.datamodel.LineAction;
import org.batfish.datamodel.Prefix;
import org.batfish.datamodel.PrefixRange;
import org.batfish.datamodel.RouteFilterLine;
import org.batfish.datamodel.RoutingProtocol;
import org.batfish.datamodel.StaticRoute;
import org.batfish.datamodel.SubRange;
import org.batfish.datamodel.TcpFlags;
import org.batfish.datamodel.routing_policy.RoutingPolicy;
import org.batfish.datamodel.routing_policy.expr.BooleanExpr;
import org.batfish.datamodel.routing_policy.expr.CommunitySetElem;
import org.batfish.datamodel.routing_policy.expr.CommunitySetExpr;
import org.batfish.datamodel.routing_policy.expr.InlineCommunitySet;
import org.batfish.datamodel.routing_policy.expr.LiteralCommunitySetElemHalf;
import org.batfish.datamodel.routing_policy.expr.MatchCommunitySet;
import org.batfish.datamodel.routing_policy.expr.MatchProtocol;
import org.batfish.datamodel.routing_policy.expr.NamedCommunitySet;
import org.batfish.datamodel.routing_policy.statement.AddCommunity;
import org.batfish.datamodel.routing_policy.statement.DeleteCommunity;
import org.batfish.datamodel.routing_policy.statement.If;
import org.batfish.datamodel.routing_policy.statement.RetainCommunity;
import org.batfish.datamodel.routing_policy.statement.SetCommunity;
import org.batfish.datamodel.routing_policy.statement.Statement;
import org.batfish.datamodel.routing_policy.statement.Statements;
import org.batfish.smt.collections.Table2;


/* TODO:
 *      * Optimize iBGP for failures=0 case
 *      * Optimize iBGP for consistent preferences case
 *
 * - Add check for block-to-external for internal prefix space
 * - Add check for block-to-internal for internal prefix space
 * - Ensure that loopbacks/next-hops are reachable
 * - Check for BGP route deflection
 */

/**
 * A class responsible for building a symbolic encoding of the network for a particular packet. The
 * encoding is heavily specialized based on the Optimizations class, and what optimizations it
 * indicates are possible.
 *
 * @author Ryan Beckett
 */
class EncoderSlice {

  static final int BITS = 0;

  static final String BGP_NETWORK_FILTER_LIST_NAME = "BGP_NETWORK_NETWORKS_FILTER";

  static final String BGP_COMMON_FILTER_LIST_NAME = "BGP_COMMON_EXPORT_POLICY";

  private Encoder _encoder;

  private int _unqId;

  private String _sliceName;

  private HeaderSpace _headerSpace;

  private Optimizations _optimizations;

  private LogicalGraph _logicalGraph;

  private SymbolicDecisions _symbolicDecisions;

  private Table2<String, Protocol, SymbolicRecord> _originationRecords;

  private SymbolicPacket _symbolicPacket;

  private Map<GraphEdge, BoolExpr> _inboundAcls;

  private Map<GraphEdge, BoolExpr> _outboundAcls;

  private Table2<String, GraphEdge, BoolExpr> _forwardsAcross;

  private Set<CommunityVar> _allCommunities;

  private Map<String, String> _namedCommunities;

  private Map<CommunityVar, List<CommunityVar>> _communityDependencies;

  /**
   * Create a new encoding slice
   *
   * @param enc The parent encoder object
   * @param h The packet headerspace of interest
   * @param batfish The batfish object
   * @param sliceName The name of this slice
   */
  EncoderSlice(Encoder enc, HeaderSpace h, IBatfish batfish, String sliceName) {
    this(enc, h, new Graph(batfish), sliceName);
  }

  /**
   * Create a new encoding slice
   *
   * @param enc The parent encoder object
   * @param h The packet headerspace of interest
   * @param graph The network graph
   * @param sliceName The name of this slice
   */
  EncoderSlice(Encoder enc, HeaderSpace h, Graph graph, String sliceName) {
    _encoder = enc;
    _unqId = -1;
    _sliceName = sliceName;
    _headerSpace = h;
    _optimizations = new Optimizations(this);
    _logicalGraph = new LogicalGraph(graph);
    _symbolicDecisions = new SymbolicDecisions();
    _originationRecords = new Table2<>();
    _symbolicPacket = new SymbolicPacket(enc.getCtx(), enc.getId(), _sliceName);

    enc.getAllVariables().put(_symbolicPacket.getDstIp().toString(), _symbolicPacket.getDstIp());
    enc.getAllVariables().put(_symbolicPacket.getSrcIp().toString(), _symbolicPacket.getSrcIp());
    enc.getAllVariables()
        .put(_symbolicPacket.getDstPort().toString(), _symbolicPacket.getDstPort());
    enc.getAllVariables()
        .put(_symbolicPacket.getSrcPort().toString(), _symbolicPacket.getSrcPort());
    enc.getAllVariables()
        .put(_symbolicPacket.getIcmpCode().toString(), _symbolicPacket.getIcmpCode());
    enc.getAllVariables()
        .put(_symbolicPacket.getIcmpType().toString(), _symbolicPacket.getIcmpType());
    enc.getAllVariables().put(_symbolicPacket.getTcpAck().toString(), _symbolicPacket.getTcpAck());
    enc.getAllVariables().put(_symbolicPacket.getTcpCwr().toString(), _symbolicPacket.getTcpCwr());
    enc.getAllVariables().put(_symbolicPacket.getTcpEce().toString(), _symbolicPacket.getTcpEce());
    enc.getAllVariables().put(_symbolicPacket.getTcpFin().toString(), _symbolicPacket.getTcpFin());
    enc.getAllVariables().put(_symbolicPacket.getTcpPsh().toString(), _symbolicPacket.getTcpPsh());
    enc.getAllVariables().put(_symbolicPacket.getTcpRst().toString(), _symbolicPacket.getTcpRst());
    enc.getAllVariables().put(_symbolicPacket.getTcpSyn().toString(), _symbolicPacket.getTcpSyn());
    enc.getAllVariables().put(_symbolicPacket.getTcpUrg().toString(), _symbolicPacket.getTcpUrg());
    enc.getAllVariables()
        .put(_symbolicPacket.getIpProtocol().toString(), _symbolicPacket.getIpProtocol());

    _inboundAcls = new HashMap<>();
    _outboundAcls = new HashMap<>();
    _forwardsAcross = new Table2<>();

    initOptimizations();
    initCommunities();
    initRedistributionProtocols();
    initVariables();
    initAclFunctions();
    initForwardingAcross();
  }

  int generateId() {
    _unqId++;
    return _unqId;
  }

  // Add a variable to the encoding
  void add(BoolExpr e) {
    _encoder.add(e);
  }

  // Symbolic mkFalse value
  public BoolExpr mkFalse() {
    return _encoder.mkFalse();
  }

  // Symbolic true value
  public BoolExpr mkTrue() {
    return _encoder.mkTrue();
  }

  // Simplify a boolean expression
  public BoolExpr simplify(BoolExpr be) {
    return (BoolExpr) (be.simplify());
  }

  // Create a symbolic boolean
  BoolExpr mkBool(boolean val) {
    return _encoder.mkBool(val);
  }

  // Symbolic boolean negation
  BoolExpr mkNot(BoolExpr e) {
    return _encoder.mkNot(e);
  }

  // Symbolic boolean disjunction
  BoolExpr mkOr(BoolExpr... vals) {
    return _encoder.mkOr(vals);
  }

  // Symbolic boolean implication
  BoolExpr mkImplies(BoolExpr e1, BoolExpr e2) {
    return getCtx().mkImplies(e1, e2);
  }

  // Symbolic greater than
  BoolExpr mkGt(Expr e1, Expr e2) {
    return _encoder.mkGt(e1, e2);
  }

  // Symbolic arithmetic subtraction
  ArithExpr mkSub(ArithExpr e1, ArithExpr e2) {
    return _encoder.mkSub(e1, e2);
  }

  // Symbolic if-then-else for booleans
  BoolExpr mkIf(BoolExpr cond, BoolExpr case1, BoolExpr case2) {
    return _encoder.mkIf(cond, case1, case2);
  }

  // Symbolic if-then-else for bit vectors
  BitVecExpr mkIf(BoolExpr cond, BitVecExpr case1, BitVecExpr case2) {
    return (BitVecExpr) _encoder.getCtx().mkITE(cond, case1, case2);
  }

  // Symbolic if-then-else for arithmetic
  ArithExpr mkIf(BoolExpr cond, ArithExpr case1, ArithExpr case2) {
    return _encoder.mkIf(cond, case1, case2);
  }

  // Symbolic equality
  BoolExpr mkEq(Expr e1, Expr e2) {
    return getCtx().mkEq(e1, e2);
  }

  // Create a symbolic integer
  ArithExpr mkInt(long l) {
    return _encoder.mkInt(l);
  }

  // Symbolic boolean conjunction
  BoolExpr mkAnd(BoolExpr... vals) {
    return _encoder.mkAnd(vals);
  }

  // Symbolic greater than or equal
  BoolExpr mkGe(Expr e1, Expr e2) {
    return _encoder.mkGe(e1, e2);
  }

  // Symbolic less than or equal
  BoolExpr mkLe(Expr e1, Expr e2) {
    return _encoder.mkLe(e1, e2);
  }

  // Symbolic less than
  BoolExpr mkLt(Expr e1, Expr e2) {
    return _encoder.mkLt(e1, e2);
  }

  // Symbolic arithmetic addition
  ArithExpr mkSum(ArithExpr e1, ArithExpr e2) {
    return _encoder.mkSum(e1, e2);
  }

  // Check equality of expressions with null checking
  BoolExpr safeEq(Expr x, Expr value) {
    if (x == null) {
      return mkTrue();
    }
    return mkEq(x, value);
  }

  // Check for equality of arithmetic expressions after adding cost, and accounting for null
  BoolExpr safeEqAdd(ArithExpr x, ArithExpr value, Integer cost) {
    if (x == null) {
      return mkTrue();
    }
    if (cost == null) {
      return mkEq(x, value);
    }
    return mkEq(x, mkSum(value, mkInt(cost)));
  }

  // Check for equality of arithmetic expressions after adding cost, and accounting for null
  BoolExpr safeEqAdd(ArithExpr x, ArithExpr value, ArithExpr cost) {
    if (x == null) {
      return mkTrue();
    }
    if (cost == null) {
      return mkEq(x, value);
    }
    return mkEq(x, mkSum(value, cost));
  }

  // Check for equality of symbolic enums after accounting for null
  <T> BoolExpr safeEqEnum(SymbolicEnum<T> x, T value) {
    if (x == null) {
      return mkTrue();
    }
    return x.checkIfValue(value);
  }

  // Check for equality of symbolic enums after accounting for null
  <T> BoolExpr safeEqEnum(SymbolicEnum<T> x, SymbolicEnum<T> y) {
    if (x == null) {
      return mkTrue();
    }
    return x.mkEq(y);
  }

  /*
   * Initialize boolean expressions to represent ACLs on each interface.
   */
  private void initAclFunctions() {
    getGraph()
        .getEdgeMap()
        .forEach(
            (router, edges) -> {
              for (GraphEdge ge : edges) {
                Interface i = ge.getStart();

                IpAccessList outbound = i.getOutgoingFilter();
                if (outbound != null) {
                  String outName = router + "_" + i.getName() + "_OUTBOUND_" + outbound.getName();
                  BoolExpr outAcl = getCtx().mkBoolConst(outName);
                  BoolExpr outAclFunc = computeACL(outbound);
                  add(mkEq(outAcl, outAclFunc));
                  _outboundAcls.put(ge, outAcl);
                }

                IpAccessList inbound = i.getIncomingFilter();
                if (inbound != null) {
                  String inName = router + "_" + i.getName() + "_INBOUND_" + inbound.getName();
                  BoolExpr inAcl = getCtx().mkBoolConst(inName);
                  BoolExpr inAclFunc = computeACL(inbound);
                  add(mkEq(inAcl, inAclFunc));
                  _inboundAcls.put(ge, inAcl);
                }
              }
            });
  }

  /*
   * Initialize boolean expressions to represent that traffic sent along
   * and edge will reach the other side of the edge.
   */
  private void initForwardingAcross() {
    _symbolicDecisions
        .getDataForwarding()
        .forEach(
            (router, edge, var) -> {
              BoolExpr inAcl;
              if (edge.getEnd() == null) {
                inAcl = mkTrue();
              } else {
                inAcl = _inboundAcls.get(edge);
                if (inAcl == null) {
                  inAcl = mkTrue();
                }
              }
              _forwardsAcross.put(router, edge, mkAnd(var, inAcl));
            });
  }

  /*
   * Initializes a dependency graph of community values and
   * community regex matches. Each community regex is mapped
   * to the collection of exact matches that it subsumes
   */
  private void initCommunities() {
    _allCommunities = findAllCommunities();
    _namedCommunities = findNamedCommunities();

    // Add an other variable for each regex community
    // Don't do this by default
    if (_optimizations.getHasExternalCommunity() && Encoder.MODEL_EXTERNAL_COMMUNITIES) {

      List<CommunityVar> others = new ArrayList<>();
      for (CommunityVar c : _allCommunities) {
        if (c.getType() == CommunityVar.Type.REGEX) {
          CommunityVar x = new CommunityVar(CommunityVar.Type.OTHER, c.getValue(), c.asLong());
          others.add(x);
        }
      }
      _allCommunities.addAll(others);
    }

    // Map community regex matches to Java regex
    Map<CommunityVar, java.util.regex.Pattern> regexes = new HashMap<>();
    for (CommunityVar c : _allCommunities) {
      if (c.getType() == CommunityVar.Type.REGEX) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(c.getValue());
        regexes.put(c, p);
      }
    }

    _communityDependencies = new HashMap<>();
    for (CommunityVar c1 : _allCommunities) {
      // map exact match to corresponding regexes
      if (c1.getType() == CommunityVar.Type.REGEX) {

        List<CommunityVar> list = new ArrayList<>();
        _communityDependencies.put(c1, list);
        java.util.regex.Pattern p = regexes.get(c1);

        for (CommunityVar c2 : _allCommunities) {
          if (c2.getType() == CommunityVar.Type.EXACT) {
            Matcher m = p.matcher(c2.getValue());
            if (m.find()) {
              list.add(c2);
            }
          }
          if (c2.getType() == CommunityVar.Type.OTHER) {
            if (c1.getValue().equals(c2.getValue())) {
              list.add(c2);
            }
          }
        }
      }
    }
  }

  /*
   * Finds all uniquely mentioned community matches
   * in the network by walking over every configuration.
   */
  Set<CommunityVar> findAllCommunities() {
    Set<CommunityVar> comms = new HashSet<>();
    getGraph()
        .getConfigurations()
        .forEach(
            (router, conf) -> {
              conf.getRoutingPolicies()
                  .forEach(
                      (name, pol) -> {
                        AstVisitor v = new AstVisitor();
                        v.visit(
                            conf,
                            pol.getStatements(),
                            stmt -> {
                              if (stmt instanceof SetCommunity) {
                                SetCommunity sc = (SetCommunity) stmt;
                                comms.addAll(findAllCommunities(conf, sc.getExpr()));
                              }
                              if (stmt instanceof AddCommunity) {
                                AddCommunity ac = (AddCommunity) stmt;
                                comms.addAll(findAllCommunities(conf, ac.getExpr()));
                              }
                              if (stmt instanceof DeleteCommunity) {
                                DeleteCommunity dc = (DeleteCommunity) stmt;
                                comms.addAll(findAllCommunities(conf, dc.getExpr()));
                              }
                              if (stmt instanceof RetainCommunity) {
                                RetainCommunity rc = (RetainCommunity) stmt;
                                comms.addAll(findAllCommunities(conf, rc.getExpr()));
                              }
                            },
                            expr -> {
                              if (expr instanceof MatchCommunitySet) {
                                MatchCommunitySet m = (MatchCommunitySet) expr;
                                CommunitySetExpr ce = m.getExpr();
                                comms.addAll(findAllCommunities(conf, ce));
                              }
                            });
                      });
            });
    return comms;
  }

  /*
   * Final all uniquely mentioned community values for a particular
   * router configuration and community set expression.
   */
  Set<CommunityVar> findAllCommunities(Configuration conf, CommunitySetExpr ce) {
    Set<CommunityVar> comms = new HashSet<>();
    if (ce instanceof InlineCommunitySet) {
      InlineCommunitySet c = (InlineCommunitySet) ce;
      for (CommunitySetElem cse : c.getCommunities()) {
        if (cse.getPrefix() instanceof LiteralCommunitySetElemHalf
            && cse.getSuffix() instanceof LiteralCommunitySetElemHalf) {
          LiteralCommunitySetElemHalf x = (LiteralCommunitySetElemHalf) cse.getPrefix();
          LiteralCommunitySetElemHalf y = (LiteralCommunitySetElemHalf) cse.getSuffix();
          int prefixInt = x.getValue();
          int suffixInt = y.getValue();
          String val = prefixInt + ":" + suffixInt;
          Long l = (((long) prefixInt) << 16) | (suffixInt);
          CommunityVar var = new CommunityVar(CommunityVar.Type.EXACT, val, l);
          comms.add(var);
        } else {
          throw new BatfishException("TODO: community non literal: " + cse);
        }
      }
    }
    if (ce instanceof NamedCommunitySet) {
      NamedCommunitySet c = (NamedCommunitySet) ce;
      String cname = c.getName();
      CommunityList cl = conf.getCommunityLists().get(cname);
      if (cl != null) {
        for (CommunityListLine line : cl.getLines()) {
          CommunityVar var = new CommunityVar(CommunityVar.Type.REGEX, line.getRegex(), null);
          comms.add(var);
        }
      }
    }
    return comms;
  }

  /*
   * Map named community sets that contain a single match
   * back to the community/regex value. This makes it
   * easier to provide intuitive counter examples.
   */
  private Map<String, String> findNamedCommunities() {
    Map<String, String> comms = new HashMap<>();
    getGraph()
        .getConfigurations()
        .forEach(
            (router, conf) -> {
              conf.getCommunityLists()
                  .forEach(
                      (name, cl) -> {
                        if (cl != null && cl.getLines().size() == 1) {
                          CommunityListLine line = cl.getLines().get(0);
                          comms.put(line.getRegex(), name);
                        }
                      });
            });
    return comms;
  }

  /*
   * Find the set of all protocols that might be redistributed into
   * protocol p given the current configuration and routing policy.
   * This is based on structure of the AST.
   */
  public Set<Protocol> findRedistributedProtocols(
      Configuration conf, RoutingPolicy pol, Protocol p) {
    Set<Protocol> protos = new HashSet<>();
    AstVisitor v = new AstVisitor();
    v.visit(
        conf,
        pol.getStatements(),
        stmt -> { } ,
        expr -> {
          if (expr instanceof MatchProtocol) {
            MatchProtocol mp = (MatchProtocol) expr;
            RoutingProtocol other = mp.getProtocol();
            Protocol otherP = Protocol.fromRoutingProtocol(other);
            if (otherP != null && otherP != p) {
              switch (other) {
                case BGP:
                  protos.add(otherP);
                  break;
                case OSPF:
                  protos.add(otherP);
                  break;
                case STATIC:
                  protos.add(otherP);
                  break;
                case CONNECTED:
                  protos.add(otherP);
                  break;
                default:
                  throw new BatfishException("Unrecognized protocol: " + other.protocolName());
              }
            }
          }
        });
    return protos;
  }

  /*
   * Initialize the map of redistributed protocols.
   */
  private void initRedistributionProtocols() {
    getGraph()
        .getConfigurations()
        .forEach(
            (router, conf) -> {
              for (Protocol proto : getProtocols().get(router)) {

                Set<Protocol> redistributed = new HashSet<>();
                redistributed.add(proto);

                _logicalGraph.getRedistributedProtocols().put(router, proto, redistributed);

                RoutingPolicy pol = getGraph().findCommonRoutingPolicy(router, proto);
                if (pol != null) {
                  Set<Protocol> ps = findRedistributedProtocols(conf, pol, proto);
                  for (Protocol p : ps) {
                    // Make sure there is actually a routing process for the other protocol
                    // For example, it might get sliced away if not relevant
                    boolean isProto = getProtocols().get(router).contains(p);
                    if (isProto) {
                      redistributed.add(p);
                    }
                  }
                }
              }
            });
  }

  /*
   * Returns are relevant logical edges for a given router and protocol.
   */
  private Set<LogicalEdge> collectAllImportLogicalEdges(
      String router, Configuration conf, Protocol proto) {
    Set<LogicalEdge> eList = new HashSet<>();
    for (ArrayList<LogicalEdge> es : _logicalGraph.getLogicalEdges().get(router, proto)) {
      for (LogicalEdge le : es) {
        if (_logicalGraph.isEdgeUsed(conf, proto, le) && le.getEdgeType() == EdgeType.IMPORT) {
          eList.add(le);
        }
      }
    }
    return eList;
  }

  /*
   * Converts a list of prefixes into a boolean expression that determines
   * if they are relevant for the symbolic packet.
   */
  public BoolExpr relevantOrigination(List<Prefix> prefixes) {
    BoolExpr acc = mkFalse();
    for (Prefix p : prefixes) {
      acc = mkOr(acc, isRelevantFor(p, _symbolicPacket.getDstIp()));
    }
    return acc;
  }

  /*
   * Get the added cost out an interface for a given routing protocol.
   */
  private Integer addedCost(Protocol proto, Interface iface) {
    if (proto.isOspf()) {
      return iface.getOspfCost();
    }
    return 1;
  }

  /*
   * Determine if an interface is active for a particular protocol.
   */
  private BoolExpr interfaceActive(Interface iface, Protocol proto) {
    BoolExpr active = mkBool(iface.getActive());
    if (proto.isOspf()) {
      active = mkAnd(active, mkBool(iface.getOspfEnabled()));
    }
    return active;
  }

  /*
   * Checks if a prefix could possible be relevant for encoding
   */
  boolean relevantPrefix(Prefix p) {
    return overlaps(_headerSpace, p);
  }

  /*
   * Checks if a prefix overlaps with the destination in a headerspace
   */
  private boolean overlaps(HeaderSpace h, Prefix p) {
    if (h.getDstIps().isEmpty()) {
      return true;
    }
    for (IpWildcard ipWildcard : h.getDstIps()) {
      Prefix p2 = ipWildcard.toPrefix();
      if (overlaps(p, p2)) {
        return true;
      }
    }
    return false;
  }

  /*
   * Checks if two prefixes ever overlap
   */
  private boolean overlaps(Prefix p1, Prefix p2) {
    long l1 = p1.getNetworkPrefix().getAddress().asLong();
    long l2 = p2.getNetworkPrefix().getAddress().asLong();
    long u1 = p1.getNetworkPrefix().getEndAddress().asLong();
    long u2 = p2.getNetworkPrefix().getEndAddress().asLong();
    return (l1 >= l2 && l1 <= u2)
        || (u1 <= u2 && u1 >= l2)
        || (u2 >= l1 && u2 <= u1)
        || (l2 >= l1 && l2 <= u1);
  }

  /*
   * Check if a prefix range match is applicable for the packet destination
   * Ip address, given the prefix length variable.
   */
  public BoolExpr isRelevantFor(ArithExpr prefixLen, PrefixRange range) {
    Prefix p = range.getPrefix();
    SubRange r = range.getLengthRange();
    long pfx = p.getNetworkAddress().asLong();
    int len = p.getPrefixLength();
    int lower = r.getStart();
    int upper = r.getEnd();

    // well formed prefix
    assert (p.getPrefixLength() < lower && lower <= upper);

    BoolExpr lowerBitsMatch = firstBitsEqual(_symbolicPacket.getDstIp(), pfx, len);
    if (lower == upper) {
      BoolExpr equalLen = mkEq(prefixLen, mkInt(lower));
      return mkAnd(equalLen, lowerBitsMatch);
    } else {
      BoolExpr lengthLowerBound = mkGe(prefixLen, mkInt(lower));
      BoolExpr lengthUpperBound = mkLe(prefixLen, mkInt(upper));
      return mkAnd(lengthLowerBound, lengthUpperBound, lowerBitsMatch);
    }
  }

  /*
   * Check if the first n bits of symbolic variable x
   * and concrete value y are equal. For example,
   * 192.0.1.0/24 would have y=int(192.0.1.0), n=24.
   */
  private BoolExpr firstBitsEqual(ArithExpr x, long y, int n) {
    assert (n >= 0 && n <= 32);
    if (n == 0) {
      return mkTrue();
    }
    long bound = (long) Math.pow(2, 32 - n);
    ArithExpr upperBound = mkInt(y + bound);
    return mkAnd(mkGe(x, mkInt(y)), mkLt(x, upperBound));
  }

  /*
   * Creates a symbolic expression representing that prefix p
   * is relevant (the first bits match) with arithmetic expression ae
   */
  public BoolExpr isRelevantFor(Prefix p, ArithExpr ae) {
    long pfx = p.getNetworkAddress().asLong();
    return firstBitsEqual(ae, pfx, p.getPrefixLength());
  }

  /*
   * Collects and returns all originated prefixes for the given
   * router as well as the protocol. Static routes and connected
   * routes are treated as originating the prefix.
   */
  public List<Prefix> getOriginatedNetworks(Configuration conf, Protocol proto) {
    List<Prefix> acc = new ArrayList<>();

    if (proto.isOspf()) {
      conf.getDefaultVrf()
          .getOspfProcess()
          .getAreas()
          .forEach(
              (areaID, area) -> {
                for (Interface iface : area.getInterfaces()) {
                  if (iface.getActive() && iface.getOspfEnabled()) {
                    acc.add(iface.getPrefix());
                  }
                }
              });
      return acc;
    }

    if (proto.isBgp()) {
      conf.getRouteFilterLists()
          .forEach(
              (name, list) -> {
                for (RouteFilterLine line : list.getLines()) {
                  if (name.contains(BGP_NETWORK_FILTER_LIST_NAME)) {
                    acc.add(line.getPrefix());
                  }
                }
              });
      return acc;
    }

    if (proto.isConnected()) {
      conf.getInterfaces()
          .forEach(
              (name, iface) -> {
                Prefix p = iface.getPrefix();
                if (p != null) {
                  acc.add(p);
                }
              });
      return acc;
    }

    if (proto.isStatic()) {
      for (StaticRoute sr : conf.getDefaultVrf().getStaticRoutes()) {
        if (sr.getNetwork() != null && !Graph.isNullRouted(sr)) {
          acc.add(sr.getNetwork());
        }
      }
      return acc;
    }

    throw new BatfishException("ERROR: getOriginatedNetworks: " + proto.name());
  }

  /*
   * Initializes the logical graph edges for the protocol-centric view
   */
  private void buildEdgeMap() {
    getGraph()
        .getEdgeMap()
        .forEach(
            (router, edges) -> {
              for (Protocol p : getProtocols().get(router)) {
                _logicalGraph.getLogicalEdges().put(router, p, new ArrayList<>());
              }
            });
  }

  /*
   * Initialize variables representing if a router chooses
   * to use a particular interface for control-plane forwarding
   */
  private void addChoiceVariables() {
    getGraph()
        .getEdgeMap()
        .forEach(
            (router, edges) -> {
              Configuration conf = getGraph().getConfigurations().get(router);

              Map<Protocol, Map<LogicalEdge, BoolExpr>> map = new HashMap<>();
              _symbolicDecisions.getChoiceVariables().put(router, map);

              for (Protocol proto : getProtocols().get(router)) {

                Map<LogicalEdge, BoolExpr> edgeMap = new HashMap<>();
                map.put(proto, edgeMap);

                for (LogicalEdge e : collectAllImportLogicalEdges(router, conf, proto)) {
                  String chName = e.getSymbolicRecord().getName() + "_choice";
                  BoolExpr choiceVar = getCtx().mkBoolConst(chName);
                  getAllVariables().put(choiceVar.toString(), choiceVar);
                  edgeMap.put(e, choiceVar);
                }
              }
            });
  }

  /*
   * Initalizes variables representing the control plane and
   * data plane final forwarding decisions
   */
  private void addForwardingVariables() {
    getGraph()
        .getEdgeMap()
        .forEach(
            (router, edges) -> {
              for (GraphEdge edge : edges) {
                String iface = edge.getStart().getName();

                String cName =
                    _encoder.getId()
                        + "_"
                        + _sliceName
                        + "CONTROL-FORWARDING_"
                        + router
                        + "_"
                        + iface;
                BoolExpr cForward = getCtx().mkBoolConst(cName);
                getAllVariables().put(cForward.toString(), cForward);
                _symbolicDecisions.getControlForwarding().put(router, edge, cForward);

                // Don't add data forwarding variable for abstract edge
                if (!edge.isAbstract()) {
                  String dName =
                      _encoder.getId()
                          + "_"
                          + _sliceName
                          + "DATA-FORWARDING_"
                          + router
                          + "_"
                          + iface;
                  BoolExpr dForward = getCtx().mkBoolConst(dName);
                  getAllVariables().put(dForward.toString(), dForward);
                  _symbolicDecisions.getDataForwarding().put(router, edge, dForward);
                }
              }
            });
  }

  /*
   * Initialize variables representing the best choice both for
   * each protocol as well as for the router as a whole
   */
  private void addBestVariables() {

    getGraph()
        .getEdgeMap()
        .forEach(
            (router, edges) -> {
              List<Protocol> allProtos = getProtocols().get(router);

              // Overall best
              for (int len = 0; len <= BITS; len++) {
                String name =
                    String.format(
                        "%d_%s%s_%s_%s_%s",
                        _encoder.getId(), _sliceName, router, "OVERALL", "BEST", "None");
                String historyName = name + "_history";
                SymbolicEnum<Protocol> h = new SymbolicEnum<>(this, allProtos, historyName);
                SymbolicRecord evBest =
                    new SymbolicRecord(this, name, router, Protocol.BEST, _optimizations, h, false);
                getAllSymbolicRecords().add(evBest);
                _symbolicDecisions.getBestNeighbor().put(router, evBest);
              }

              // Best per protocol
              if (!_optimizations.getSliceHasSingleProtocol().contains(router)) {
                for (Protocol proto : getProtocols().get(router)) {
                  String name =
                      String.format(
                          "%d_%s%s_%s_%s_%s",
                          _encoder.getId(), _sliceName, router, proto.name(), "BEST", "None");
                  String historyName = name + "_history";

                  SymbolicEnum<Protocol> h =
                      null; //new SymbolicEnum<>(this, allProtos, historyName);

                  for (int len = 0; len <= BITS; len++) {
                    SymbolicRecord evBest =
                        new SymbolicRecord(this, name, router, proto, _optimizations, h, false);
                    getAllSymbolicRecords().add(evBest);
                    _symbolicDecisions.getBestNeighborPerProtocol().put(router, proto, evBest);
                  }
                }
              }
            });
  }

  // TODO: only create this variable when necessary
  // I.E., when there is a originated prefix overlapping with the
  // actual destination IP for the query.
  private void addOriginationVariables() {
    getGraph()
        .getConfigurations()
        .forEach(
            (router, conf) -> {
              for (Protocol proto : getProtocols().get(router)) {
                if (proto.isBgp() || proto.isOspf()) {
                  String name =
                      String.format(
                          "%d_%s%s_%s_%s_%s",
                          _encoder.getId(), _sliceName, router, proto.name(), "ORIGINATE", "None");
                  SymbolicRecord origin =
                      new SymbolicRecord(
                          this, name, router, Protocol.BEST, _optimizations, null, false);
                  getAllSymbolicRecords().add(origin);
                  _originationRecords.put(router, proto, origin);
                }
              }
            });
  }

  /*
   * Initialize all control-plane message symbolic records.
   * Also maps each logical graph edge to its opposite edge.
   */
  private void addSymbolicRecords() {
    Map<String, Map<Protocol, Map<GraphEdge, ArrayList<LogicalEdge>>>> importInverseMap =
        new HashMap<>();
    Map<String, Map<Protocol, Map<GraphEdge, ArrayList<LogicalEdge>>>> exportInverseMap =
        new HashMap<>();
    Map<String, Map<Protocol, SymbolicRecord>> singleExportMap = new HashMap<>();

    // add edge EXPORT and IMPORT state variables
    getGraph()
        .getEdgeMap()
        .forEach(
            (router, edges) -> {
              Map<Protocol, SymbolicRecord> singleProtoMap;
              singleProtoMap = new HashMap<>();
              Map<Protocol, Map<GraphEdge, ArrayList<LogicalEdge>>> importEnumMap;
              importEnumMap = new HashMap<>();
              Map<Protocol, Map<GraphEdge, ArrayList<LogicalEdge>>> exportEnumMap;
              exportEnumMap = new HashMap<>();

              singleExportMap.put(router, singleProtoMap);
              importInverseMap.put(router, importEnumMap);
              exportInverseMap.put(router, exportEnumMap);

              for (Protocol proto : getProtocols().get(router)) {

                boolean useSingleExport =
                    _optimizations.getSliceCanKeepSingleExportVar().get(router, proto);

                Map<GraphEdge, ArrayList<LogicalEdge>> importGraphEdgeMap = new HashMap<>();
                Map<GraphEdge, ArrayList<LogicalEdge>> exportGraphEdgeMap = new HashMap<>();

                importEnumMap.put(proto, importGraphEdgeMap);
                exportEnumMap.put(proto, exportGraphEdgeMap);

                for (GraphEdge e : edges) {

                  Configuration conf = getGraph().getConfigurations().get(router);

                  if (getGraph().isEdgeUsed(conf, proto, e)) {

                    ArrayList<LogicalEdge> importEdgeList = new ArrayList<>();
                    ArrayList<LogicalEdge> exportEdgeList = new ArrayList<>();
                    importGraphEdgeMap.put(e, importEdgeList);
                    exportGraphEdgeMap.put(e, exportEdgeList);

                    for (int len = 0; len <= BITS; len++) {

                      String ifaceName = e.getStart().getName();

                      if (!proto.isConnected()) {
                        // mkIf we use a single set of export variables, then make sure
                        // to reuse the existing variables instead of creating new ones
                        if (useSingleExport) {
                          SymbolicRecord singleVars = singleExportMap.get(router).get(proto);
                          SymbolicRecord ev1;
                          if (singleVars == null) {
                            String name =
                                String.format(
                                    "%d_%s%s_%s_%s_%s",
                                    _encoder.getId(),
                                    _sliceName,
                                    router,
                                    proto.name(),
                                    "SINGLE-EXPORT",
                                    "");
                            ev1 =
                                new SymbolicRecord(
                                    this,
                                    name,
                                    router,
                                    proto,
                                    _optimizations,
                                    null,
                                    e.isAbstract());
                            singleProtoMap.put(proto, ev1);
                            getAllSymbolicRecords().add(ev1);
                          } else {
                            ev1 = singleVars;
                          }
                          LogicalEdge eExport = new LogicalEdge(e, EdgeType.EXPORT, ev1);
                          exportEdgeList.add(eExport);

                        } else {
                          String name =
                              String.format(
                                  "%d_%s%s_%s_%s_%s",
                                  _encoder.getId(),
                                  _sliceName,
                                  router,
                                  proto.name(),
                                  "EXPORT",
                                  ifaceName);

                          SymbolicRecord ev1 =
                              new SymbolicRecord(
                                  this, name, router, proto, _optimizations, null, e.isAbstract());
                          LogicalEdge eExport = new LogicalEdge(e, EdgeType.EXPORT, ev1);
                          exportEdgeList.add(eExport);
                          getAllSymbolicRecords().add(ev1);
                        }
                      }

                      boolean notNeeded =
                          _optimizations
                              .getSliceCanCombineImportExportVars()
                              .get(router)
                              .get(proto)
                              .contains(e);

                      Interface i = e.getStart();
                      Prefix p = i.getPrefix();

                      boolean doModel = !(proto.isConnected() && p != null && !relevantPrefix(p));
                      // Optimization: Don't model the connected interfaces that aren't relevant
                      if (doModel) {
                        if (notNeeded) {
                          String name =
                              String.format(
                                  "%d_%s%s_%s_%s_%s",
                                  _encoder.getId(),
                                  _sliceName,
                                  router,
                                  proto.name(),
                                  "IMPORT",
                                  ifaceName);
                          SymbolicRecord ev2 = new SymbolicRecord(name, proto);
                          LogicalEdge eImport = new LogicalEdge(e, EdgeType.IMPORT, ev2);
                          importEdgeList.add(eImport);
                        } else {
                          String name =
                              String.format(
                                  "%d_%s%s_%s_%s_%s",
                                  _encoder.getId(),
                                  _sliceName,
                                  router,
                                  proto.name(),
                                  "IMPORT",
                                  ifaceName);
                          SymbolicRecord ev2 =
                              new SymbolicRecord(
                                  this, name, router, proto, _optimizations, null, e.isAbstract());
                          LogicalEdge eImport = new LogicalEdge(e, EdgeType.IMPORT, ev2);
                          importEdgeList.add(eImport);
                          getAllSymbolicRecords().add(ev2);
                        }
                      }
                    }

                    List<ArrayList<LogicalEdge>> es =
                        _logicalGraph.getLogicalEdges().get(router, proto);
                    ArrayList<LogicalEdge> allEdges = new ArrayList<>();
                    allEdges.addAll(importEdgeList);
                    allEdges.addAll(exportEdgeList);
                    es.add(allEdges);
                  }
                }
              }
            });

    // Build a map to find the opposite of a given edge
    _logicalGraph
        .getLogicalEdges()
        .forEach(
            (router, edgeLists) -> {
              for (Protocol proto : getProtocols().get(router)) {

                for (ArrayList<LogicalEdge> edgeList : edgeLists.get(proto)) {

                  for (int i = 0; i < edgeList.size(); i++) {

                    LogicalEdge e = edgeList.get(i);

                    GraphEdge edge = e.getEdge();
                    Map<GraphEdge, ArrayList<LogicalEdge>> m;

                    // System.out.println("i: " + i);
                    // System.out.println("proto: " + proto.name());
                    // System.out.println("edge: " + edge + ", " + e.getEdgeType());

                    if (edge.getPeer() != null) {

                      if (e.getEdgeType() == EdgeType.IMPORT) {
                        m = exportInverseMap.get(edge.getPeer()).get(proto);

                      } else {
                        m = importInverseMap.get(edge.getPeer()).get(proto);
                      }

                      if (m != null) {
                        GraphEdge otherEdge = getGraph().getOtherEnd().get(edge);
                        ArrayList<LogicalEdge> list = m.get(otherEdge);
                        if (list.size() > 0) {
                          LogicalEdge other = list.get(0);
                          _logicalGraph.getOtherEnd().put(e, other);
                        }
                      }
                    }
                  }
                }
              }
            });
  }

  /*
   * Initialize the optimizations object, which computes all
   * applicable optimizations for the current encoding slice.
   */
  private void initOptimizations() {
    _optimizations.computeOptimizations();
  }

  /*
   * Check if this is the main slice
   */
  public boolean isMainSlice() {
    return _sliceName.equals("") || _sliceName.equals(Encoder.MAIN_SLICE_NAME);
  }

  /*
   * Initialize all environment symbolic records for BGP.
   */
  private void addEnvironmentVariables() {
    // mkIf not the main slice, just use the main slice
    if (!isMainSlice()) {
      Map<LogicalEdge, SymbolicRecord> envs = _logicalGraph.getEnvironmentVars();
      EncoderSlice main = _encoder.getMainSlice();
      LogicalGraph lg = main.getLogicalGraph();
      Map<LogicalEdge, SymbolicRecord> existing = lg.getEnvironmentVars();
      envs.putAll(existing);
      return;
    }

    // Otherwise create it anew
    getGraph()
        .getConfigurations()
        .forEach(
            (router, conf) -> {
              for (Protocol proto : getProtocols().get(router)) {
                if (proto.isBgp()) {
                  _logicalGraph
                      .getLogicalEdges()
                      .get(router, proto)
                      .forEach(
                          eList -> {
                            eList.forEach(
                                e -> {
                                  if (e.getEdgeType() == EdgeType.IMPORT) {
                                    GraphEdge ge = e.getEdge();
                                    BgpNeighbor n = getGraph().getEbgpNeighbors().get(ge);
                                    if (n != null && ge.getEnd() == null) {

                                      if (!isMainSlice()) {
                                        LogicalGraph lg = _encoder.getMainSlice().getLogicalGraph();
                                        SymbolicRecord r = lg.getEnvironmentVars().get(e);
                                        _logicalGraph.getEnvironmentVars().put(e, r);
                                      } else {
                                        String address;
                                        if (n.getAddress() == null) {
                                          address = "null";
                                        } else {
                                          address = n.getAddress().toString();
                                        }
                                        String ifaceName = "ENV-" + address;
                                        String name =
                                            String.format(
                                                "%d_%s%s_%s_%s_%s",
                                                _encoder.getId(),
                                                _sliceName,
                                                router,
                                                proto.name(),
                                                "EXPORT",
                                                ifaceName);
                                        SymbolicRecord vars =
                                            new SymbolicRecord(
                                                this,
                                                name,
                                                router,
                                                proto,
                                                _optimizations,
                                                null,
                                                ge.isAbstract());
                                        getAllSymbolicRecords().add(vars);
                                        _logicalGraph.getEnvironmentVars().put(e, vars);
                                      }
                                    }
                                  }
                                });
                          });
                }
              }
            });
  }

  /*
   * Initialize all symbolic variables for the encoding slice
   */
  private void initVariables() {
    buildEdgeMap();
    addForwardingVariables();
    addBestVariables();
    // addOriginationVariables();
    addSymbolicRecords();
    addChoiceVariables();
    addEnvironmentVariables();
  }

  /*
   * Constraint each variable to fall within a valid range.
   * Metric cost is protocol dependent and need not have an
   * upper bound, since overflow is modeled.
   *
   * dstIp, srcIp:        [0,2^32)
   * dstPort, srcPort:    [0,2^16)
   * icmpType, protocol:  [0,2^8)
   * icmpCode:            [0,2^4)
   *
   * prefix length:       [0,2^32)
   * admin distance:      [0,2^8)
   * BGP med, local-pref: [0,2^32)
   * metric cost:         [0,2^8) if environment
   * metric cost:         [0,2^16) otherwise
   */
  private void addBoundConstraints() {

    ArithExpr upperBound4 = mkInt((long) Math.pow(2, 4));
    ArithExpr upperBound8 = mkInt((long) Math.pow(2, 8));
    ArithExpr upperBound16 = mkInt((long) Math.pow(2, 16));
    ArithExpr upperBound32 = mkInt((long) Math.pow(2, 32));
    ArithExpr zero = mkInt(0);

    // Valid 32 bit integers
    add(mkGe(_symbolicPacket.getDstIp(), zero));
    add(mkGe(_symbolicPacket.getSrcIp(), zero));
    add(mkLt(_symbolicPacket.getDstIp(), upperBound32));
    add(mkLt(_symbolicPacket.getSrcIp(), upperBound32));

    // Valid 16 bit integer
    add(mkGe(_symbolicPacket.getDstPort(), zero));
    add(mkGe(_symbolicPacket.getSrcPort(), zero));
    add(mkLt(_symbolicPacket.getDstPort(), upperBound16));
    add(mkLt(_symbolicPacket.getSrcPort(), upperBound16));

    // Valid 8 bit integer
    add(mkGe(_symbolicPacket.getIcmpType(), zero));
    add(mkGe(_symbolicPacket.getIpProtocol(), zero));
    add(mkLt(_symbolicPacket.getIcmpType(), upperBound8));
    add(mkLe(_symbolicPacket.getIpProtocol(), upperBound8));

    // Valid 4 bit integer
    add(mkGe(_symbolicPacket.getIcmpCode(), zero));
    add(mkLt(_symbolicPacket.getIcmpCode(), upperBound4));

    for (SymbolicRecord e : getAllSymbolicRecords()) {
      if (e.getRouterId() != null) {
        add(mkGe(e.getRouterId(), zero));
      }

      if (e.getAdminDist() != null) {
        add(mkGe(e.getAdminDist(), zero));
        add(mkLt(e.getAdminDist(), upperBound8));
      }
      if (e.getMed() != null) {
        add(mkGe(e.getMed(), zero));
        add(mkLt(e.getMed(), upperBound32));
      }
      if (e.getLocalPref() != null) {
        add(mkGe(e.getLocalPref(), zero));
        add(mkLt(e.getLocalPref(), upperBound32));
      }
      if (e.getMetric() != null) {
        add(mkGe(e.getMetric(), zero));
        if (e.isEnv()) {
          add(mkLt(e.getMetric(), upperBound8));
        }
        add(mkLt(e.getMetric(), upperBound16));
      }
      if (e.getIgpMetric() != null) {
        add(mkGe(e.getIgpMetric(), zero));
      }
      if (e.getPrefixLength() != null) {
        add(mkGe(e.getPrefixLength(), zero));
        add(mkLe(e.getPrefixLength(), mkInt(32)));
      }
    }
  }

  /*
   * Constraints each community regex match. A regex match
   * will be true, if either one of its subsumed exact values is
   * attached to the message, or some other community that matches
   * the regex is instead:
   *
   * c_regex = (c_1 or ... or c_n) or c_other
   *
   * where the regex matches c_i. The regex match is determined
   * ahead of time based on the configuration.
   */
  private void addCommunityConstraints() {
    for (SymbolicRecord r : getAllSymbolicRecords()) {
      r.getCommunities()
          .forEach(
              (cvar, e) -> {
                if (cvar.getType() == CommunityVar.Type.REGEX) {
                  BoolExpr acc = mkFalse();
                  List<CommunityVar> deps = _communityDependencies.get(cvar);
                  for (CommunityVar dep : deps) {
                    BoolExpr depExpr = r.getCommunities().get(dep);
                    acc = mkOr(acc, depExpr);
                  }
                  BoolExpr regex = mkEq(acc, e);
                  add(regex);
                }
              });
    }
  }

  /*
   * A collection of default values to fill in missing values for
   * comparison. These are needed because the optimizations might
   * remove various attributes from messages when unnecessary.
   */

  public int defaultAdminDistance(Configuration conf, Protocol proto) {
    RoutingProtocol rp = Protocol.toRoutingProtocol(proto);
    return rp.getDefaultAdministrativeCost(conf.getConfigurationFormat());
  }

  public int defaultId() {
    return 0;
  }

  public int defaultMetric() {
    return 0;
  }

  public int defaultMed(Protocol proto) {
    if (proto.isBgp()) {
      return 100;
    }
    return 0;
  }

  public int defaultLocalPref() {
    return 100;
  }

  public int defaultLength() {
    return 0;
  }

  public int defaultIgpMetric() {
    return 0;
  }

  public BitVecExpr defaultOspfType() {
    return getCtx().mkBV(0, 2); // OI
  }

  /*
   * Returns the symbolic record for logical edge e.
   * This method is necessary, because optimizations might
   * decide that certain records can be merged together.
   */
  private SymbolicRecord correctVars(LogicalEdge e) {
    SymbolicRecord vars = e.getSymbolicRecord();
    if (!vars.getIsUsed()) {
      return _logicalGraph.getOtherEnd().get(e).getSymbolicRecord();
    }
    return vars;
  }

  /*
   * Creates a symbolic test between a record representing the best
   * field and another field (vars). mkIf the vars field is missing,
   * then the value is filled in with the default value.
   *
   * An assumption is that if best != null, then vars != null
   */
  private BoolExpr equalHelper(Expr best, Expr vars, Expr defaultVal) {
    BoolExpr tru = mkTrue();
    if (vars == null) {
      if (best != null) {
        return mkEq(best, defaultVal);
      } else {
        return tru;
      }
    } else {
      return mkEq(best, vars);
    }
  }

  /*
   * Creates a test to check for equal protocol histories
   * after accounting for null values introduced by optimizations
   */
  public BoolExpr equalHistories(SymbolicRecord best, SymbolicRecord vars) {
    BoolExpr history;
    if (best.getProtocolHistory() == null) {
      history = mkTrue();
    } else {
      if (vars.getProtocolHistory() == null) {
        history = best.getProtocolHistory().checkIfValue(vars.getProto());
      } else {
        history = best.getProtocolHistory().mkEq(vars.getProtocolHistory());
      }
    }
    /* if (best.getProtocolHistory() == null || vars.getProtocolHistory() == null) {
        history = mkTrue();
    } else {
        history = best.getProtocolHistory().mkEq(vars.getProtocolHistory());
    } */
    return history;
  }

  /*
   * Creates a test to check for equal bgp internal
   * tags after accounting for null values introduced by optimizations
   */
  public BoolExpr equalBgpInternal(SymbolicRecord best, SymbolicRecord vars) {
    if (best.getBgpInternal() == null || vars.getBgpInternal() == null) {
      return mkTrue();
    } else {
      return mkEq(best.getBgpInternal(), vars.getBgpInternal());
    }
  }

  /*
   * Creates a test to check for equal bgp client id tags after
   * accounting for the possibility of null values.
   */
  private BoolExpr equalClientIds(SymbolicRecord best, SymbolicRecord vars) {
    if (best.getClientId() == null) {
      return mkTrue();
    } else {
      if (vars.getClientId() == null) {
        // Lookup the actual originator id
        return best.getClientId().checkIfValue(0);
      } else {
        return best.getClientId().mkEq(vars.getClientId());
      }
    }
  }

  /*
   * Creates a test to check for equal ospf areas
   * tags after accounting for null values introduced by optimizations
   */
  private BoolExpr equalAreas(SymbolicRecord best, SymbolicRecord vars, LogicalEdge e) {
    BoolExpr equalOspfArea;
    boolean hasBestArea = (best.getOspfArea() != null && best.getOspfArea().getBitVec() != null);
    boolean hasVarsArea = (vars.getOspfArea() != null && vars.getOspfArea().getBitVec() != null);
    if (hasBestArea) {
      Interface iface = e.getEdge().getStart();
      if (hasVarsArea) {
        equalOspfArea = best.getOspfArea().mkEq(vars.getOspfArea());
      } else if (iface.getOspfAreaName() != null) {
        equalOspfArea = best.getOspfArea().checkIfValue(iface.getOspfAreaName());
      } else {
        equalOspfArea = best.getOspfArea().isDefaultValue();
      }
    } else {
      equalOspfArea = mkTrue();
    }
    return equalOspfArea;
  }

  /*
   * Creates a symbolic test to check for equal ospf types (OI, OIA, E1, E2)
   * after accounting for null values introduced by optimizations
   */
  private BoolExpr equalTypes(SymbolicRecord best, SymbolicRecord vars) {
    BoolExpr equalOspfType;
    boolean hasBestType = (best.getOspfType() != null && best.getOspfType().getBitVec() != null);
    boolean hasVarsType = (vars.getOspfType() != null && vars.getOspfType().getBitVec() != null);
    if (hasVarsType) {
      equalOspfType = best.getOspfType().mkEq(vars.getOspfType());
    } else if (hasBestType) {
      equalOspfType = best.getOspfType().isDefaultValue();
    } else {
      equalOspfType = mkTrue();
    }
    return equalOspfType;
  }

  /*
   * Creates a symbolic test to check for equal router IDs
   * after accounting for null values introduced by optimizations
   */
  private BoolExpr equalIds(
      SymbolicRecord best, SymbolicRecord vars, Configuration conf, Protocol proto, LogicalEdge e) {

    BoolExpr equalId;
    if (vars.getRouterId() == null) {
      if (best.getRouterId() == null || e == null) {
        equalId = mkTrue();
      } else {
        Long peerId = _logicalGraph.findRouterId(e, proto);
        equalId = mkEq(best.getRouterId(), mkInt(peerId));
      }
    } else {
      equalId = mkEq(best.getRouterId(), vars.getRouterId());
    }
    return equalId;
  }

  private BoolExpr equalCommunities(SymbolicRecord best, SymbolicRecord vars) {
    BoolExpr acc = mkTrue();
    for (Map.Entry<CommunityVar, BoolExpr> entry : best.getCommunities().entrySet()) {
      CommunityVar cvar = entry.getKey();
      BoolExpr var = entry.getValue();
      BoolExpr other = vars.getCommunities().get(cvar);
      if (other == null) {
        acc = mkAnd(acc, mkNot(var));
      } else {
        acc = mkAnd(acc, mkEq(var, other));
      }
    }
    return acc;
  }

  /*
   * Check for equality of a (best) symbolic record and another
   * symbolic record (vars). It checks pairwise that all fields
   * are equal, while filling in values missing due to optimizations
   * with default values based on the protocol.
   * mkIf there is no corresponding edge e, then the value null can be used
   */
  public BoolExpr equal(
      Configuration conf,
      Protocol proto,
      SymbolicRecord best,
      SymbolicRecord vars,
      @Nullable LogicalEdge e,
      boolean compareCommunities) {

    ArithExpr defaultLocal = mkInt(defaultLocalPref());
    ArithExpr defaultAdmin = mkInt(defaultAdminDistance(conf, proto));
    ArithExpr defaultMet = mkInt(defaultMetric());
    ArithExpr defaultMed = mkInt(defaultMed(proto));
    ArithExpr defaultLen = mkInt(defaultLength());
    ArithExpr defaultIgp = mkInt(defaultIgpMetric());

    BoolExpr equalLen;
    BoolExpr equalAd;
    BoolExpr equalLp;
    BoolExpr equalMet;
    BoolExpr equalMed;
    BoolExpr equalOspfArea;
    BoolExpr equalOspfType;
    BoolExpr equalId;
    BoolExpr equalHistory;
    BoolExpr equalBgpInternal;
    BoolExpr equalClientIds;
    BoolExpr equalIgpMet;
    BoolExpr equalCommunities;

    equalLen = equalHelper(best.getPrefixLength(), vars.getPrefixLength(), defaultLen);
    equalAd = equalHelper(best.getAdminDist(), vars.getAdminDist(), defaultAdmin);
    equalLp = equalHelper(best.getLocalPref(), vars.getLocalPref(), defaultLocal);
    equalMet = equalHelper(best.getMetric(), vars.getMetric(), defaultMet);
    equalMed = equalHelper(best.getMed(), vars.getMed(), defaultMed);
    equalIgpMet = equalHelper(best.getIgpMetric(), vars.getIgpMetric(), defaultIgp);

    equalOspfType = equalTypes(best, vars);
    equalOspfArea = equalAreas(best, vars, e);

    equalId = equalIds(best, vars, conf, proto, e);
    equalHistory = equalHistories(best, vars);
    equalBgpInternal = equalBgpInternal(best, vars);
    equalClientIds = equalClientIds(best, vars);
    equalCommunities = (compareCommunities ? equalCommunities(best, vars) : mkTrue());

    return mkAnd(
        equalLen,
        equalAd,
        equalLp,
        equalMet,
        equalMed,
        equalOspfArea,
        equalOspfType,
        equalId,
        equalHistory,
        equalBgpInternal,
        equalClientIds,
        equalIgpMet,
        equalCommunities);
  }

  /*
   * Helper function to check if one expression is greater than
   * another accounting for null values introduced by optimizations.
   */
  private BoolExpr geBetterHelper(Expr best, Expr vars, Expr defaultVal, boolean less) {
    BoolExpr fal = mkFalse();
    if (vars == null) {
      if (best != null) {
        if (less) {
          return mkLt(best, defaultVal);
        } else {
          return mkGt(best, defaultVal);
        }
      } else {
        return fal;
      }
    } else {
      if (less) {
        return mkLt(best, vars);
      } else {
        return mkGt(best, vars);
      }
    }
  }

  /*
   * Helper function to check if one expression is equal to
   * another accounting for null values introduced by optimizations.
   */
  private BoolExpr geEqualHelper(Expr best, Expr vars, Expr defaultVal) {
    if (vars == null) {
      if (best != null) {
        return mkEq(best, defaultVal);
      } else {
        return mkTrue();
      }
    } else {
      return mkEq(best, vars);
    }
  }

  /*
   * Check if a (best) symbolic record is better than another
   * symbolic record (vars). This is done using a recursive lexicographic
   * encoding. The encoding is as follows:
   *
   * (best.length > vars.length) or
   * (best.length = vars.length) and (
   *    (best.adminDist < vars.adminDist) or
   *    (best.adminDist = vars.adminDist) and (
   *     ...
   *    )
   * )
   *
   * This recursive encoding introduces a new variable for each subexpressions,
   * which ends up being much more efficient than expanding out options.
   */
  private BoolExpr greaterOrEqual(
      Configuration conf, Protocol proto, SymbolicRecord best, SymbolicRecord vars, LogicalEdge e) {

    ArithExpr defaultLocal = mkInt(defaultLocalPref());
    ArithExpr defaultAdmin = mkInt(defaultAdminDistance(conf, proto));
    ArithExpr defaultMet = mkInt(defaultMetric());
    ArithExpr defaultMed = mkInt(defaultMed(proto));
    ArithExpr defaultLen = mkInt(defaultLength());
    ArithExpr defaultIgp = mkInt(defaultIgpMetric());
    ArithExpr defaultId = mkInt(0);
    BitVecExpr defaultOspfType = defaultOspfType();

    BoolExpr betterLen =
        geBetterHelper(best.getPrefixLength(), vars.getPrefixLength(), defaultLen, false);
    BoolExpr equalLen = geEqualHelper(best.getPrefixLength(), vars.getPrefixLength(), defaultLen);

    BoolExpr betterAd =
        geBetterHelper(best.getAdminDist(), vars.getAdminDist(), defaultAdmin, true);
    BoolExpr equalAd = geEqualHelper(best.getAdminDist(), vars.getAdminDist(), defaultAdmin);

    BoolExpr betterLp =
        geBetterHelper(best.getLocalPref(), vars.getLocalPref(), defaultLocal, false);
    BoolExpr equalLp = geEqualHelper(best.getLocalPref(), vars.getLocalPref(), defaultLocal);

    BoolExpr betterMet = geBetterHelper(best.getMetric(), vars.getMetric(), defaultMet, true);
    BoolExpr equalMet = geEqualHelper(best.getMetric(), vars.getMetric(), defaultMet);

    BoolExpr betterMed = geBetterHelper(best.getMed(), vars.getMed(), defaultMed, true);
    BoolExpr equalMed = geEqualHelper(best.getMed(), vars.getMed(), defaultMed);

    BitVecExpr bestType = (best.getOspfType() == null ? null : best.getOspfType().getBitVec());
    BitVecExpr varsType = (vars.getOspfType() == null ? null : vars.getOspfType().getBitVec());

    BoolExpr betterOspfType = geBetterHelper(bestType, varsType, defaultOspfType, true);
    BoolExpr equalOspfType = geEqualHelper(bestType, varsType, defaultOspfType);

    BoolExpr betterInternal =
        geBetterHelper(best.getBgpInternal(), vars.getBgpInternal(), mkFalse(), true);
    BoolExpr equalInternal = geEqualHelper(best.getBgpInternal(), vars.getBgpInternal(), mkFalse());

    BoolExpr betterIgpMet =
        geBetterHelper(best.getIgpMetric(), vars.getIgpMetric(), defaultIgp, true);
    BoolExpr equalIgpMet = geEqualHelper(best.getIgpMetric(), vars.getIgpMetric(), defaultIgp);

    BoolExpr tiebreak;

    if (vars.getRouterId() != null) {
      tiebreak = mkLe(best.getRouterId(), vars.getRouterId());
    } else if (best.getRouterId() != null) {
      if (e == null) {
        tiebreak = mkLe(best.getRouterId(), defaultId);
      } else {
        Long peerId = _logicalGraph.findRouterId(e, proto);
        tiebreak = mkLe(best.getRouterId(), mkInt(peerId));
      }
    } else {
      tiebreak = mkTrue();
    }

    BoolExpr b = mkAnd(equalOspfType, tiebreak);
    BoolExpr b1 = mkOr(betterOspfType, b);
    BoolExpr b2 = mkAnd(equalIgpMet, b1);
    BoolExpr b3 = mkOr(betterIgpMet, b2);
    BoolExpr b4 = mkAnd(equalInternal, b3);
    BoolExpr b5 = mkOr(betterInternal, b4);
    BoolExpr b6 = mkAnd(equalMed, b5);
    BoolExpr b7 = mkOr(betterMed, b6);
    BoolExpr b8 = mkAnd(equalMet, b7);
    BoolExpr b9 = mkOr(betterMet, b8);
    BoolExpr b10 = mkAnd(equalLp, b9);
    BoolExpr b11 = mkOr(betterLp, b10);
    BoolExpr b12 = mkAnd(equalAd, b11);
    BoolExpr b13 = mkOr(betterAd, b12);
    BoolExpr b14 = mkAnd(equalLen, b13);
    return mkOr(betterLen, b14);
  }

  /*
   * Constraints that specify that the best choice is
   * better than all alternatives, and is at least one of the choices:
   *
   * (1) if no options are valid, then best is not valid
   * (2) if some option is valid, then we have the following:
   *
   * (best <= best_prot1) and ... and (best <= best_protn)
   * (best =  best_prot1) or  ... or  (best =  best_protn)
   */
  private void addBestOverallConstraints() {
    getGraph()
        .getConfigurations()
        .forEach(
            (router, conf) -> {

              // These constraints will be added at the protocol-level when a single protocol
              if (!_optimizations.getSliceHasSingleProtocol().contains(router)) {

                boolean someProto = false;

                BoolExpr acc = null;
                BoolExpr somePermitted = null;
                SymbolicRecord best = _symbolicDecisions.getBestNeighbor().get(router);

                for (Protocol proto : getProtocols().get(router)) {
                  someProto = true;

                  SymbolicRecord bestVars =
                      _symbolicDecisions.getBestVars(_optimizations, router, proto);

                  if (somePermitted == null) {
                    somePermitted = bestVars.getPermitted();
                  } else {
                    somePermitted = mkOr(somePermitted, bestVars.getPermitted());
                  }

                  BoolExpr val =
                      mkAnd(
                          bestVars.getPermitted(), equal(conf, proto, best, bestVars, null, true));
                  if (acc == null) {
                    acc = val;
                  } else {
                    acc = mkOr(acc, val);
                  }
                  add(
                      mkImplies(
                          bestVars.getPermitted(),
                          greaterOrEqual(conf, proto, best, bestVars, null)));
                }

                if (someProto) {
                  if (acc != null) {
                    add(mkEq(somePermitted, best.getPermitted()));
                    add(mkImplies(somePermitted, acc));
                  }
                } else {
                  add(mkNot(best.getPermitted()));
                }
              }
            });
  }

  /*
   * Constrains each protocol-best record similarly to the overall
   * best record. It will be better than all choices and equal to
   * at least one of them.
   */
  private void addBestPerProtocolConstraints() {
    getGraph()
        .getConfigurations()
        .forEach(
            (router, conf) -> {
              for (Protocol proto : getProtocols().get(router)) {

                SymbolicRecord bestVars =
                    _symbolicDecisions.getBestVars(_optimizations, router, proto);
                BoolExpr acc = null;
                BoolExpr somePermitted = null;

                for (LogicalEdge e : collectAllImportLogicalEdges(router, conf, proto)) {

                  SymbolicRecord vars = correctVars(e);

                  if (somePermitted == null) {
                    somePermitted = vars.getPermitted();
                  } else {
                    somePermitted = mkOr(somePermitted, vars.getPermitted());
                  }

                  BoolExpr v =
                      mkAnd(vars.getPermitted(), equal(conf, proto, bestVars, vars, e, true));
                  if (acc == null) {
                    acc = v;
                  } else {
                    acc = mkOr(acc, v);
                  }
                  add(
                      mkImplies(
                          vars.getPermitted(), greaterOrEqual(conf, proto, bestVars, vars, e)));
                }

                if (acc != null) {
                  add(mkEq(somePermitted, bestVars.getPermitted()));
                  add(mkImplies(somePermitted, acc));
                }
              }
            });
  }

  /*
   * Constraints that define a choice for a given protocol
   * to be when a particular import is equal to the best choice.
   * For example:
   *
   * choice_bgp_Serial0 = (import_Serial0 = best_bgp)
   */
  private void addChoicePerProtocolConstraints() {
    getGraph()
        .getConfigurations()
        .forEach(
            (router, conf) -> {
              for (Protocol proto : getProtocols().get(router)) {
                SymbolicRecord bestVars =
                    _symbolicDecisions.getBestVars(_optimizations, router, proto);
                for (LogicalEdge e : collectAllImportLogicalEdges(router, conf, proto)) {
                  SymbolicRecord vars = correctVars(e);
                  BoolExpr choice = _symbolicDecisions.getChoiceVariables().get(router, proto, e);
                  BoolExpr isBest = equal(conf, proto, bestVars, vars, e, false);
                  add(mkEq(choice, mkAnd(vars.getPermitted(), isBest)));
                }
              }
            });
  }

  /*
   * Constraints that define control-plane forwarding.
   * mkIf there is some valid import, then control plane forwarding
   * will occur out an interface when this is the best choice.
   * Otherwise, it will not occur.
   */
  private void addControlForwardingConstraints() {
    getGraph()
        .getConfigurations()
        .forEach(
            (router, conf) -> {
              boolean someEdge = false;

              SymbolicRecord best = _symbolicDecisions.getBestNeighbor().get(router);
              Map<GraphEdge, BoolExpr> cfExprs = new HashMap<>();

              Set<GraphEdge> constrained = new HashSet<>();

              for (Protocol proto : getProtocols().get(router)) {

                for (LogicalEdge e : collectAllImportLogicalEdges(router, conf, proto)) {

                  someEdge = true;
                  constrained.add(e.getEdge());

                  SymbolicRecord vars = correctVars(e);
                  BoolExpr choice = _symbolicDecisions.getChoiceVariables().get(router, proto, e);

                  // TODO: do we need this equality check?
                  BoolExpr isBest = mkAnd(choice, equal(conf, proto, best, vars, e, false));

                  GraphEdge ge = e.getEdge();
                  BoolExpr cForward = _symbolicDecisions.getControlForwarding().get(router, ge);
                  add(mkImplies(isBest, cForward));

                  // record the negation as well
                  cfExprs.merge(ge, isBest, (a, b) -> mkOr(a, b));
                }
              }

              // For edges that are never used, we constraint them to not be forwarded out of
              for (GraphEdge ge : getGraph().getEdgeMap().get(router)) {
                if (!constrained.contains(ge)) {
                  BoolExpr cForward = _symbolicDecisions.getControlForwarding().get(router, ge);
                  add(mkNot(cForward));
                }
              }

              // Handle the case that the router has no protocol running
              if (!someEdge) {
                for (GraphEdge ge : getGraph().getEdgeMap().get(router)) {
                  BoolExpr cForward = _symbolicDecisions.getControlForwarding().get(router, ge);
                  add(mkNot(cForward));
                }
              } else {
                _logicalGraph
                    .getLogicalEdges()
                    .get(router)
                    .forEach(
                        (proto, eList) -> {
                          eList.forEach(
                              edges -> {
                                edges.forEach(
                                    le -> {
                                      GraphEdge ge = le.getEdge();
                                      BoolExpr expr = cfExprs.get(ge);
                                      BoolExpr cForward =
                                          _symbolicDecisions.getControlForwarding().get(router, ge);
                                      if (expr != null) {
                                        add(mkImplies(mkNot(expr), mkNot(cForward)));
                                      } else {
                                        add(mkNot(cForward));
                                      }
                                    });
                              });
                        });
              }
            });
  }

  /*
   * Convert a set of wildcards and a packet field to a symbolic boolean expression
   */
  private BoolExpr computeWildcardMatch(Set<IpWildcard> wcs, ArithExpr field) {
    BoolExpr acc = mkFalse();
    for (IpWildcard wc : wcs) {
      if (!wc.isPrefix()) {
        throw new BatfishException("ERROR: computeDstWildcards, non sequential mask detected");
      }
      acc = mkOr(acc, isRelevantFor(wc.toPrefix(), field));
    }
    return (BoolExpr) acc.simplify();
  }

  /*
   * Convert a set of ranges and a packet field to a symbolic boolean expression
   */
  private BoolExpr computeValidRange(Set<SubRange> ranges, ArithExpr field) {
    BoolExpr acc = mkFalse();
    for (SubRange range : ranges) {
      int start = range.getStart();
      int end = range.getEnd();
      if (start == end) {
        BoolExpr val = mkEq(field, mkInt(start));
        acc = mkOr(acc, val);
      } else {
        BoolExpr val1 = mkGe(field, mkInt(start));
        BoolExpr val2 = mkLe(field, mkInt(end));
        acc = mkOr(acc, mkAnd(val1, val2));
      }
    }
    return (BoolExpr) acc.simplify();
  }

  /*
   * Convert a Tcp flag to a boolean expression on the symbolic packet
   */
  private BoolExpr computeTcpFlags(TcpFlags flags) {
    BoolExpr acc = mkTrue();
    if (flags.getUseAck()) {
      acc = mkAnd(acc, mkEq(_symbolicPacket.getTcpAck(), mkBool(flags.getAck())));
    }
    if (flags.getUseCwr()) {
      acc = mkAnd(acc, mkEq(_symbolicPacket.getTcpCwr(), mkBool(flags.getCwr())));
    }
    if (flags.getUseEce()) {
      acc = mkAnd(acc, mkEq(_symbolicPacket.getTcpEce(), mkBool(flags.getEce())));
    }
    if (flags.getUseFin()) {
      acc = mkAnd(acc, mkEq(_symbolicPacket.getTcpFin(), mkBool(flags.getFin())));
    }
    if (flags.getUsePsh()) {
      acc = mkAnd(acc, mkEq(_symbolicPacket.getTcpPsh(), mkBool(flags.getPsh())));
    }
    if (flags.getUseRst()) {
      acc = mkAnd(acc, mkEq(_symbolicPacket.getTcpRst(), mkBool(flags.getRst())));
    }
    if (flags.getUseSyn()) {
      acc = mkAnd(acc, mkEq(_symbolicPacket.getTcpSyn(), mkBool(flags.getSyn())));
    }
    if (flags.getUseUrg()) {
      acc = mkAnd(acc, mkEq(_symbolicPacket.getTcpUrg(), mkBool(flags.getUrg())));
    }
    return (BoolExpr) acc.simplify();
  }

  /*
   * Convert Tcp flags to a boolean expression on the symbolic packet
   */
  private BoolExpr computeTcpFlags(List<TcpFlags> flags) {
    BoolExpr acc = mkFalse();
    for (TcpFlags fs : flags) {
      acc = mkOr(acc, computeTcpFlags(fs));
    }
    return (BoolExpr) acc.simplify();
  }

  /*
   * Convert a set of ip protocols to a boolean expression on the symbolic packet
   */
  private BoolExpr computeIpProtocols(Set<IpProtocol> ipProtos) {
    BoolExpr acc = mkFalse();
    for (IpProtocol proto : ipProtos) {
      ArithExpr protoNum = mkInt(proto.number());
      acc = mkOr(acc, mkEq(protoNum, _symbolicPacket.getIpProtocol()));
    }
    return (BoolExpr) acc.simplify();
  }

  /*
   * Convert an Access Control List (ACL) to a symbolic boolean expression.
   * The default action in an ACL is to deny all traffic.
   */
  private BoolExpr computeACL(IpAccessList acl) {
    // Check if there is an ACL first
    if (acl == null) {
      return mkTrue();
    }

    BoolExpr acc = mkFalse();

    List<IpAccessListLine> lines = new ArrayList<>(acl.getLines());
    Collections.reverse(lines);

    for (IpAccessListLine l : lines) {
      BoolExpr local = null;

      if (l.getDstIps() != null) {
        local = computeWildcardMatch(l.getDstIps(), _symbolicPacket.getDstIp());
      }

      if (l.getSrcIps() != null) {
        BoolExpr val = computeWildcardMatch(l.getSrcIps(), _symbolicPacket.getSrcIp());
        local = (local == null ? val : mkAnd(local, val));
      }

      if (l.getDscps() != null && !l.getDscps().isEmpty()) {
        throw new BatfishException("detected dscps");
      }

      if (l.getDstPorts() != null && !l.getDstPorts().isEmpty()) {
        BoolExpr val = computeValidRange(l.getDstPorts(), _symbolicPacket.getDstPort());
        local = (local == null ? val : mkAnd(local, val));
      }

      if (l.getSrcPorts() != null && !l.getSrcPorts().isEmpty()) {
        BoolExpr val = computeValidRange(l.getSrcPorts(), _symbolicPacket.getSrcPort());
        local = (local == null ? val : mkAnd(local, val));
      }

      if (l.getEcns() != null && !l.getEcns().isEmpty()) {
        throw new BatfishException("detected ecns");
      }

      if (l.getTcpFlags() != null && !l.getTcpFlags().isEmpty()) {
        BoolExpr val = computeTcpFlags(l.getTcpFlags());
        local = (local == null ? val : mkAnd(local, val));
      }

      if (l.getFragmentOffsets() != null && !l.getFragmentOffsets().isEmpty()) {
        throw new BatfishException("detected fragment offsets");
      }

      if (l.getIcmpCodes() != null && !l.getIcmpCodes().isEmpty()) {
        BoolExpr val = computeValidRange(l.getIcmpCodes(), _symbolicPacket.getIcmpCode());
        local = (local == null ? val : mkAnd(local, val));
      }

      if (l.getIcmpTypes() != null && !l.getIcmpTypes().isEmpty()) {
        BoolExpr val = computeValidRange(l.getIcmpTypes(), _symbolicPacket.getIcmpType());
        local = (local == null ? val : mkAnd(local, val));
      }

      if (l.getStates() != null && !l.getStates().isEmpty()) {
        throw new BatfishException("detected states");
      }

      if (l.getIpProtocols() != null && !l.getIpProtocols().isEmpty()) {
        BoolExpr val = computeIpProtocols(l.getIpProtocols());
        local = (local == null ? val : mkAnd(local, val));
      }

      if (l.getNotDscps() != null && !l.getNotDscps().isEmpty()) {
        throw new BatfishException("detected NOT dscps");
      }

      if (l.getNotDstIps() != null && !l.getNotDstIps().isEmpty()) {
        throw new BatfishException("detected NOT dst ip");
      }

      if (l.getNotSrcIps() != null && !l.getNotSrcIps().isEmpty()) {
        throw new BatfishException("detected NOT src ip");
      }

      if (l.getNotDstPorts() != null && !l.getNotDstPorts().isEmpty()) {
        throw new BatfishException("detected NOT dst port");
      }

      if (l.getNotSrcPorts() != null && !l.getNotSrcPorts().isEmpty()) {
        throw new BatfishException("detected NOT src port");
      }

      if (l.getNotEcns() != null && !l.getNotEcns().isEmpty()) {
        throw new BatfishException("detected NOT ecns");
      }

      if (l.getNotIcmpCodes() != null && !l.getNotIcmpCodes().isEmpty()) {
        throw new BatfishException("detected NOT icmp codes");
      }

      if (l.getNotIcmpTypes() != null && !l.getNotIcmpTypes().isEmpty()) {
        throw new BatfishException("detected NOT icmp types");
      }

      if (l.getNotFragmentOffsets() != null && !l.getNotFragmentOffsets().isEmpty()) {
        throw new BatfishException("detected NOT fragment offset");
      }

      if (l.getNotIpProtocols() != null && !l.getNotIpProtocols().isEmpty()) {
        throw new BatfishException("detected NOT ip protocols");
      }

      if (local != null) {
        BoolExpr ret;
        if (l.getAction() == LineAction.ACCEPT) {
          ret = mkTrue();
        } else {
          ret = mkFalse();
        }

        if (l.getNegate()) {
          local = mkNot(local);
        }

        acc = mkIf(local, ret, acc);
      }
    }

    return acc;
  }

  private LogicalEdge foo(GraphEdge ge, Protocol proto) {
    List<ArrayList<LogicalEdge>> es =
        getLogicalGraph().getLogicalEdges().get(ge.getRouter(), proto);
    LogicalEdge e = null;
    if (es != null) {
      for (ArrayList<LogicalEdge> ess : es) {
        for (LogicalEdge edge : ess) {
          if (edge.getEdge().equals(ge)) {
            e = edge;
            break;
          }
        }
      }
    }
    return e;
  }

  /*
   * Constraints for the final data plane forwarding behavior.
   * Forwarding occurs in the data plane if the control plane decides
   * to use an interface, and no ACL blocks the packet:
   *
   * data_fwd(iface) = control_fwd(iface) and not acl(iface)
   */
  private void addDataForwardingConstraints() {

    getGraph()
        .getEdgeMap()
        .forEach(
            (router, edges) -> {
              for (GraphEdge ge : edges) {

                // setup forwarding for non-abstract edges
                if (!ge.isAbstract()) {

                  BoolExpr fwd = mkFalse();

                  BoolExpr cForward = _symbolicDecisions.getControlForwarding().get(router, ge);
                  BoolExpr dForward = _symbolicDecisions.getDataForwarding().get(router, ge);

                  // for each abstract control edge,
                  // if that edge is on and its neighbor slices has next hop forwarding
                  // out the current edge ge, the we use ge.
                  for (GraphEdge ge2 : getGraph().getEdgeMap().get(router)) {
                    if (ge2.isAbstract()) {
                      BoolExpr ctrlFwd =
                          getSymbolicDecisions().getControlForwarding().get(router, ge2);
                      Graph.BgpSendType st = getGraph().peerType(ge2);
                      // mkIf Route reflectors, then next hop based on ID
                      if (st == Graph.BgpSendType.TO_RR) {
                        SymbolicRecord record =
                            getSymbolicDecisions().getBestNeighbor().get(router);

                        // adjust for iBGP in main slice
                        BoolExpr acc = mkFalse();
                        if (isMainSlice()) {
                          for (Map.Entry<String, Integer> entry :
                              getGraph().getOriginatorId().entrySet()) {
                            String r = entry.getKey();
                            Integer id = entry.getValue();
                            EncoderSlice s = _encoder.getSlice(r);
                            BoolExpr outEdge =
                                s.getSymbolicDecisions().getDataForwarding().get(router, ge);
                            acc = mkOr(acc, mkAnd(record.getClientId().checkIfValue(id), outEdge));
                          }
                        }

                        fwd = mkOr(fwd, mkAnd(ctrlFwd, acc));
                      } else { // Otherwise, we know the next hop statically

                        // adjust for iBGP in main slice
                        if (isMainSlice()) {
                          EncoderSlice s = _encoder.getSlice(ge2.getPeer());
                          BoolExpr outEdge =
                              s.getSymbolicDecisions().getDataForwarding().get(router, ge);
                          fwd = mkOr(fwd, mkAnd(ctrlFwd, outEdge));
                        }
                      }
                    }
                  }

                  fwd = mkOr(fwd, cForward);

                  BoolExpr acl = _outboundAcls.get(ge);
                  if (acl == null) {
                    acl = mkTrue();
                  }
                  BoolExpr notBlocked = mkAnd(fwd, acl);
                  add(mkEq(notBlocked, dForward));

                  // System.out.println("FORWARDING FOR: " + ge);
                  // System.out.println(mkEq(notBlocked,dForward).simplify());
                }
              }
            });
  }

  /*
   * Creates the transfer function to represent import filters
   * between two symbolic records. The import filter depends
   * heavily on the protocol.
   */
  private void addImportConstraint(
      LogicalEdge e,
      SymbolicRecord varsOther,
      Configuration conf,
      Protocol proto,
      GraphEdge ge,
      String router,
      List<Prefix> originations) {

    SymbolicRecord vars = e.getSymbolicRecord();

    Interface iface = ge.getStart();

    ArithExpr failed = getSymbolicFailures().getFailedVariable(e.getEdge());
    BoolExpr notFailed = mkEq(failed, mkInt(0));

    if (vars.getIsUsed()) {

      if (proto.isConnected()) {
        Prefix p = iface.getPrefix();
        BoolExpr relevant =
            mkAnd(
                interfaceActive(iface, proto),
                isRelevantFor(p, _symbolicPacket.getDstIp()),
                notFailed);
        BoolExpr per = vars.getPermitted();
        BoolExpr len = safeEq(vars.getPrefixLength(), mkInt(p.getPrefixLength()));
        BoolExpr ad = safeEq(vars.getAdminDist(), mkInt(1));
        BoolExpr lp = safeEq(vars.getLocalPref(), mkInt(0));
        BoolExpr met = safeEq(vars.getMetric(), mkInt(0));
        BoolExpr values = mkAnd(per, len, ad, lp, met);
        add(mkIf(relevant, values, mkNot(vars.getPermitted())));
      }

      if (proto.isStatic()) {
        List<StaticRoute> srs =
            getGraph().getStaticRoutes().get(router).get(iface.getName()); // should exist
        assert (srs != null);
        BoolExpr acc = mkNot(vars.getPermitted());
        for (StaticRoute sr : srs) {
          Prefix p = sr.getNetwork();
          BoolExpr relevant =
              mkAnd(
                  interfaceActive(iface, proto),
                  isRelevantFor(p, _symbolicPacket.getDstIp()),
                  notFailed);
          BoolExpr per = vars.getPermitted();
          BoolExpr len = safeEq(vars.getPrefixLength(), mkInt(p.getPrefixLength()));
          BoolExpr ad = safeEq(vars.getAdminDist(), mkInt(sr.getAdministrativeCost()));
          BoolExpr lp = safeEq(vars.getLocalPref(), mkInt(0));
          BoolExpr met = safeEq(vars.getMetric(), mkInt(0));
          BoolExpr values = mkAnd(per, len, ad, lp, met);
          acc = mkIf(relevant, values, acc);
        }
        add(acc);
      }

      if (proto.isOspf() || proto.isBgp()) {
        BoolExpr val = mkNot(vars.getPermitted());

        if (varsOther != null) {

          //BoolExpr isRoot = relevantOrigination(originations);
          BoolExpr active = interfaceActive(iface, proto);

          // Handle iBGP by checking reachability to the next hop to send messages
          boolean isNonClient =
              (proto.isBgp()) && (getGraph().peerType(ge) != Graph.BgpSendType.TO_EBGP);
          boolean isClient =
              (proto.isBgp()) && (getGraph().peerType(ge) == Graph.BgpSendType.TO_RR);

          BoolExpr receiveMessage;
          String currentRouter = ge.getRouter();
          String peerRouter = ge.getPeer();

          if (_encoder.getModelIgp() && isNonClient) {
            // Lookup reachabilty based on peer next-hop
            receiveMessage = _encoder.getSliceReachability().get(currentRouter).get(peerRouter);
          } else if (_encoder.getModelIgp() && isClient) {
            // Lookup reachability based on client id tag to find next hop
            BoolExpr acc = mkTrue();
            for (Map.Entry<String, Integer> entry : getGraph().getOriginatorId().entrySet()) {
              String r = entry.getKey();
              Integer id = entry.getValue();
              if (!r.equals(currentRouter)) {
                BoolExpr reach = _encoder.getSliceReachability().get(currentRouter).get(r);
                acc = mkAnd(acc, mkImplies(varsOther.getClientId().checkIfValue(id), reach));
              }
            }
            receiveMessage = acc;

            // Just check if the link is failed
          } else {
            receiveMessage = notFailed;
          }

          // Take into account BGP loop prevention
          // The path length will prevent any isolated loops
          BoolExpr loop;
          if (ge.getPeer() != null) {
            String peer = ge.getPeer();
            GraphEdge gePeer = getGraph().getOtherEnd().get(ge);
            loop = getSymbolicDecisions().getControlForwarding().get(peer, gePeer);
          } else {
            loop = mkFalse();
          }

          BoolExpr usable = mkAnd(mkNot(loop), active, varsOther.getPermitted(), receiveMessage);

          BoolExpr importFunction;
          RoutingPolicy pol = getGraph().findImportRoutingPolicy(router, proto, e.getEdge());

          if (Encoder.ENABLE_DEBUGGING && pol != null) {
            System.out.println("Import Policy: " + pol.getName());
          }

          List<Statement> statements;
          if (pol == null) {
            Statements.StaticStatement s = new Statements.StaticStatement(Statements.ExitAccept);
            statements = Collections.singletonList(s);
          } else {
            statements = pol.getStatements();
          }

          TransferFunctionSSA f =
              new TransferFunctionSSA(
                  this, conf, varsOther, vars, proto, proto, statements, 0, ge, false);
          importFunction = f.compute();

          BoolExpr acc = mkIf(usable, importFunction, val);

          if (Encoder.ENABLE_DEBUGGING) {
            System.out.println("IMPORT FUNCTION: " + router + " " + varsOther.getName());
            System.out.println(importFunction.simplify());
            System.out.println("\n\n");
          }

          add(acc);

        } else {
          add(val);
        }
      }
    }
  }

  /*
   * Creates the transfer function to represent export filters
   * between two symbolic records. The import filter depends
   * heavily on the protocol.
   */
  private boolean addExportConstraint(
      LogicalEdge e,
      SymbolicRecord varsOther,
      Configuration conf,
      Protocol proto,
      GraphEdge ge,
      String router,
      boolean usedExport,
      List<Prefix> originations) {

    SymbolicRecord vars = e.getSymbolicRecord();

    Interface iface = ge.getStart();

    ArithExpr failed = getSymbolicFailures().getFailedVariable(e.getEdge());
    BoolExpr notFailed = mkEq(failed, mkInt(0));

    // only add constraints once when using a single copy of export variables
    if (!_optimizations.getSliceCanKeepSingleExportVar().get(router).get(proto) || !usedExport) {

      if (proto.isConnected()) {
        BoolExpr val = mkNot(vars.getPermitted());
        add(val);
      }

      if (proto.isStatic()) {
        BoolExpr val = mkNot(vars.getPermitted());
        add(val);
      }

      if (proto.isOspf() || proto.isBgp()) {
        Integer cost = addedCost(proto, iface);
        BoolExpr val = mkNot(vars.getPermitted());
        BoolExpr active = interfaceActive(iface, proto);

        // Apply BGP export policy and cost based on peer type
        // (1) EBGP --> ALL
        // (2) CLIENT --> ALL
        // (3) NONCLIENT --> EBGP, CLIENT
        boolean isNonClientEdge =
            proto.isBgp() && getGraph().peerType(ge) != Graph.BgpSendType.TO_EBGP;
        boolean isClientEdge =
            proto.isBgp() && getGraph().peerType(ge) == Graph.BgpSendType.TO_CLIENT;

        boolean isInternalExport =
            varsOther.isBest() && _optimizations.getNeedBgpInternal().contains(router);
        BoolExpr doExport = mkTrue();
        if (isInternalExport && proto.isBgp() && isNonClientEdge) {
          if (isClientEdge) {
            cost = 0;
          } else {
            doExport = mkNot(varsOther.getBgpInternal());
            cost = 0;
          }
        }

        // Split Horizon (Don't re-export routes to the neighbor from which you received it)
        // BoolExpr splitHorizon = getSymbolicDecisions().getControlForwarding().get(router, ge);

        BoolExpr usable = mkAnd(active, doExport, varsOther.getPermitted(), notFailed);
        BoolExpr acc;
        RoutingPolicy pol = getGraph().findExportRoutingPolicy(router, proto, e);

        if (Encoder.ENABLE_DEBUGGING && pol != null) {
          System.out.println("Export policy (" + _sliceName + "," + ge + "): " + pol.getName());
        }

        // We have to wrap this with the right thing for some reason
        List<Statement> statements;
        Statements.StaticStatement s1 = new Statements.StaticStatement(Statements.ExitAccept);
        Statements.StaticStatement s2 = new Statements.StaticStatement(Statements.ExitReject);

        if (proto.isOspf()) {
          If i = new If();
          List<Statement> stmts =
              (pol == null ? Collections.singletonList(s2) : pol.getStatements());
          i.setTrueStatements(Collections.singletonList(s1));
          i.setFalseStatements(stmts);
          BooleanExpr expr = new MatchProtocol(RoutingProtocol.OSPF);
          i.setGuard(expr);
          statements = Collections.singletonList(i);
        } else {
          statements = (pol == null ? Collections.singletonList(s1) : pol.getStatements());
        }

        TransferFunctionSSA f =
            new TransferFunctionSSA(
                this, conf, varsOther, vars, proto, proto, statements, cost, ge, true);
        acc = f.compute();

        acc = mkIf(usable, acc, val);

        List<Long> areas = new ArrayList<>(getGraph().getAreaIds().get(router));
        for (Prefix p : originations) {
          BoolExpr notIbgpExport = mkNot(mkBool(isNonClientEdge));
          BoolExpr ifaceUp = interfaceActive(iface, proto);
          BoolExpr relevantPrefix = isRelevantFor(p, _symbolicPacket.getDstIp());
          BoolExpr relevant = mkAnd(notIbgpExport, ifaceUp, relevantPrefix);

          int adminDistance = defaultAdminDistance(conf, proto);
          int prefixLength = p.getPrefixLength();

          BoolExpr values;

          if (proto.isBgp()) {

            // TODO: delete the unused values below
            SymbolicRecord r = new SymbolicRecord();
            r.setPermitted(mkTrue());
            r.setLocalPref(mkInt(0));
            r.setAdminDist(mkInt(adminDistance));
            r.setMetric(mkInt(0));
            r.setMed(mkInt(100));
            r.setPrefixLength(mkInt(prefixLength));
            r.setOspfType(new SymbolicOspfType(this, OspfType.O));
            r.setOspfArea(
                new SymbolicEnum<>(this, areas, iface.getOspfAreaName())); // TODO: NAMING BUG
            r.setBgpInternal(mkFalse());
            r.setIgpMetric(mkInt(0));

            // send the client id for route reflector purposes
            Integer x = getGraph().getOriginatorId().get(router);
            int clientId = (!isClientEdge && !isNonClientEdge ? 0 : x);
            r.setClientId(new SymbolicOriginatorId(this, clientId));

            Map<CommunityVar, BoolExpr> comms = new HashMap<>();
            vars.getCommunities()
                .forEach(
                    (cvar, b) -> {
                      comms.put(cvar, mkFalse());
                    });
            r.setCommunities(comms);

            TransferFunctionSSA origin =
                new TransferFunctionSSA(
                    this, conf, r, vars, proto, proto, statements, cost, ge, true);
            values = origin.compute();

          } else {
            // TODO: delete the unused values below
            BoolExpr per = vars.getPermitted();
            BoolExpr lp = safeEq(vars.getLocalPref(), mkInt(0));
            BoolExpr ad = safeEq(vars.getAdminDist(), mkInt(adminDistance));
            BoolExpr met = safeEq(vars.getMetric(), mkInt(cost));
            BoolExpr med = safeEq(vars.getMed(), mkInt(100));
            BoolExpr len = safeEq(vars.getPrefixLength(), mkInt(prefixLength));
            BoolExpr type = safeEqEnum(vars.getOspfType(), OspfType.O);
            BoolExpr area = safeEqEnum(vars.getOspfArea(), iface.getOspfAreaName());
            BoolExpr internal = safeEq(vars.getBgpInternal(), mkFalse());
            BoolExpr igpMet = safeEq(vars.getIgpMetric(), mkInt(0));
            BoolExpr comms = mkTrue();
            for (Map.Entry<CommunityVar, BoolExpr> entry : vars.getCommunities().entrySet()) {
              comms = mkAnd(comms, mkNot(entry.getValue()));
            }

            values = mkAnd(per, lp, ad, met, med, len, type, area, internal, igpMet, comms);
          }

          acc = mkIf(relevant, values, acc);
        }

        add(acc);

        if (Encoder.ENABLE_DEBUGGING) {
          System.out.println("EXPORT: " + router + " " + varsOther.getName() + " " + ge);
          System.out.println(acc.simplify());
          System.out.println("\n\n");
        }
      }
      return true;
    }
    return false;
  }

  /*
   * Constraints that define relationships between various messages
   * in the network. The same transfer function abstraction is used
   * for both import and export constraints by relating different collections
   * of variables.
   */
  private void addTransferFunction() {
    getGraph()
        .getConfigurations()
        .forEach(
            (router, conf) -> {
              for (Protocol proto : getProtocols().get(router)) {
                Boolean usedExport = false;
                List<Prefix> originations = getOriginatedNetworks(conf, proto);
                for (ArrayList<LogicalEdge> eList :
                    _logicalGraph.getLogicalEdges().get(router, proto)) {
                  for (LogicalEdge e : eList) {
                    GraphEdge ge = e.getEdge();

                    if (getGraph().isEdgeUsed(conf, proto, ge)) {
                      SymbolicRecord varsOther;
                      switch (e.getEdgeType()) {
                        case IMPORT:
                          varsOther = _logicalGraph.findOtherVars(e);
                          addImportConstraint(e, varsOther, conf, proto, ge, router, originations);
                          break;

                        case EXPORT:
                          varsOther = _symbolicDecisions.getBestNeighbor().get(router);
                          usedExport =
                              addExportConstraint(
                                  e, varsOther, conf, proto, ge, router, usedExport, originations);
                          break;

                        default:
                          break;
                      }
                    }
                  }
                }
              }
            });
  }

  /*
   * Constraints that ensure the protocol choosen by the best choice is accurate.
   * This is important because redistribution depends on the protocol used
   * in the actual FIB.
   */
  private void addHistoryConstraints() {
    //_symbolicDecisions.getBestNeighborPerProtocol().forEach((router, proto, vars) -> {
    //    add(mkImplies(vars.getPermitted(), vars.getProtocolHistory().checkIfValue(proto)));
    //});

    _symbolicDecisions
        .getBestNeighbor()
        .forEach(
            (router, vars) -> {
              if (_optimizations.getSliceHasSingleProtocol().contains(router)) {
                Protocol proto = getProtocols().get(router).get(0);
                add(mkImplies(vars.getPermitted(), vars.getProtocolHistory().checkIfValue(proto)));
              }
            });
  }

  /*
   * For performance reasons, we add constraints that if a message is not
   * valid, then the other variables will use default values. This speeds
   * up the solver significantly.
   */
  private void addUnusedDefaultValueConstraints() {
    for (SymbolicRecord vars : getAllSymbolicRecords()) {

      BoolExpr notPermitted = mkNot(vars.getPermitted());
      ArithExpr zero = mkInt(0);

      if (vars.getAdminDist() != null) {
        add(mkImplies(notPermitted, mkEq(vars.getAdminDist(), zero)));
      }
      if (vars.getMed() != null) {
        add(mkImplies(notPermitted, mkEq(vars.getMed(), zero)));
      }
      if (vars.getLocalPref() != null) {
        add(mkImplies(notPermitted, mkEq(vars.getLocalPref(), zero)));
      }
      if (vars.getPrefixLength() != null) {
        add(mkImplies(notPermitted, mkEq(vars.getPrefixLength(), zero)));
      }
      if (vars.getMetric() != null) {
        add(mkImplies(notPermitted, mkEq(vars.getMetric(), zero)));
      }
      if (vars.getOspfArea() != null) {
        add(mkImplies(notPermitted, vars.getOspfArea().isDefaultValue()));
      }
      if (vars.getOspfType() != null) {
        add(mkImplies(notPermitted, vars.getOspfType().isDefaultValue()));
      }
      if (vars.getProtocolHistory() != null) {
        add(mkImplies(notPermitted, vars.getProtocolHistory().isDefaultValue()));
      }
      if (vars.getBgpInternal() != null) {
        add(mkImplies(notPermitted, mkNot(vars.getBgpInternal())));
      }
      if (vars.getClientId() != null) {
        add(mkImplies(notPermitted, vars.getClientId().isDefaultValue()));
      }
      if (vars.getIgpMetric() != null) {
        add(mkImplies(notPermitted, mkEq(vars.getIgpMetric(), zero)));
      }
      if (vars.getRouterId() != null) {
        add(mkImplies(notPermitted, mkEq(vars.getRouterId(), zero)));
      }
      vars.getCommunities()
          .forEach(
              (cvar, e) -> {
                add(mkImplies(notPermitted, mkNot(e)));
              });
    }
  }

  /*
   * Constraints that ensure when a link is not active, that messages
   * can not flow across the link.
   */
  private void addInactiveLinkConstraints() {
    _logicalGraph
        .getLogicalEdges()
        .forEach(
            (router, proto, edges) -> {
              for (ArrayList<LogicalEdge> es : edges) {
                for (LogicalEdge e : es) {
                  Interface iface = e.getEdge().getStart();
                  if (!getGraph().isInterfaceActive(proto, iface)) {
                    BoolExpr per = e.getSymbolicRecord().getPermitted();
                    if (per != null) {
                      add(mkNot(per));
                    }
                  }
                }
              }
            });
  }

  /*
   * Create boolean expression for a variable being within a bound.
   */
  private BoolExpr boundConstraint(ArithExpr e, long lower, long upper) {
    if (lower > upper) {
      throw new BatfishException("Invalid range: " + lower + "-" + upper);
    } else if (lower == upper) {
      return mkEq(e, mkInt(lower));
    } else {
      BoolExpr x = mkGe(e, mkInt(lower));
      BoolExpr y = mkLe(e, mkInt(upper));
      return mkAnd(x, y);
    }
  }

  /*
   * Create a boolean expression for a variable being within a prefix bound.
   */
  private BoolExpr boundConstraint(ArithExpr e, Prefix p) {
    Prefix n = p.getNetworkPrefix();
    long lower = n.getAddress().asLong();
    long upper = n.getEndAddress().asLong();
    return boundConstraint(e, lower, upper);
  }

  /*
   * Create a boolean expression for a variable being withing an IpWildCard bound
   */
  private BoolExpr ipWildCardBound(ArithExpr e, IpWildcard ipWildcard) {
    if (!ipWildcard.isPrefix()) {
      throw new BatfishException("Unsupported IP wildcard: " + ipWildcard);
    }
    Prefix p = ipWildcard.toPrefix().getNetworkPrefix();
    return boundConstraint(e, p);
  }

  /*
   * Create a boolean expression for a variable being within a particular subrange.
   */
  private BoolExpr subRangeBound(ArithExpr e, SubRange r) {
    long lower = r.getStart();
    long upper = r.getEnd();
    return boundConstraint(e, lower, upper);
  }

  /*
   * Add constraints for the type of packets we will consider in the model.
   * This can include restrictions on any packet field such as dstIp, protocol etc.
   */
  private void addHeaderSpaceConstraint() {

    BoolExpr acc;

    if (_headerSpace.getDstIps().size() > 0) {
      acc = mkFalse();
      for (IpWildcard ipWildcard : _headerSpace.getDstIps()) {
        BoolExpr bound = ipWildCardBound(_symbolicPacket.getDstIp(), ipWildcard);
        acc = mkOr(acc, bound);
      }
      add(acc);
    }

    if (_headerSpace.getNotDstIps().size() > 0) {
      acc = mkTrue();
      for (IpWildcard ipWildcard : _headerSpace.getNotDstIps()) {
        BoolExpr bound = ipWildCardBound(_symbolicPacket.getDstIp(), ipWildcard);
        acc = mkAnd(acc, mkNot(bound));
      }
      add(acc);
    }

    if (_headerSpace.getSrcIps().size() > 0) {
      acc = mkFalse();
      for (IpWildcard ipWildcard : _headerSpace.getSrcIps()) {
        BoolExpr bound = ipWildCardBound(_symbolicPacket.getSrcIp(), ipWildcard);
        acc = mkOr(acc, bound);
      }
      add(acc);
    }

    if (_headerSpace.getNotSrcIps().size() > 0) {
      acc = mkTrue();
      for (IpWildcard ipWildcard : _headerSpace.getNotSrcIps()) {
        BoolExpr bound = ipWildCardBound(_symbolicPacket.getSrcIp(), ipWildcard);
        acc = mkAnd(acc, mkNot(bound));
      }
      add(acc);
    }

    if (_headerSpace.getSrcOrDstIps().size() > 0) {
      acc = mkFalse();
      for (IpWildcard ipWildcard : _headerSpace.getSrcOrDstIps()) {
        BoolExpr bound1 = ipWildCardBound(_symbolicPacket.getDstIp(), ipWildcard);
        BoolExpr bound2 = ipWildCardBound(_symbolicPacket.getSrcIp(), ipWildcard);
        acc = mkOr(acc, bound1, bound2);
      }
      add(acc);
    }

    if (_headerSpace.getDstPorts().size() > 0) {
      acc = mkFalse();
      for (SubRange subRange : _headerSpace.getDstPorts()) {
        BoolExpr bound = subRangeBound(_symbolicPacket.getDstPort(), subRange);
        acc = mkOr(acc, bound);
      }
      add(acc);
    }

    if (_headerSpace.getNotDstPorts().size() > 0) {
      acc = mkTrue();
      for (SubRange subRange : _headerSpace.getNotDstPorts()) {
        BoolExpr bound = subRangeBound(_symbolicPacket.getDstPort(), subRange);
        acc = mkAnd(acc, mkNot(bound));
      }
      add(acc);
    }

    if (_headerSpace.getSrcPorts().size() > 0) {
      acc = mkFalse();
      for (SubRange subRange : _headerSpace.getSrcPorts()) {
        BoolExpr bound = subRangeBound(_symbolicPacket.getDstPort(), subRange);
        acc = mkOr(acc, bound);
      }
      add(acc);
    }

    if (_headerSpace.getNotSrcPorts().size() > 0) {
      acc = mkTrue();
      for (SubRange subRange : _headerSpace.getNotSrcPorts()) {
        BoolExpr bound = subRangeBound(_symbolicPacket.getDstPort(), subRange);
        acc = mkAnd(acc, mkNot(bound));
      }
      add(acc);
    }

    if (_headerSpace.getSrcOrDstPorts().size() > 0) {
      acc = mkFalse();
      for (SubRange subRange : _headerSpace.getSrcOrDstPorts()) {
        BoolExpr bound1 = subRangeBound(_symbolicPacket.getDstPort(), subRange);
        BoolExpr bound2 = subRangeBound(_symbolicPacket.getSrcPort(), subRange);
        acc = mkOr(acc, bound1, bound2);
      }
      add(acc);
    }

    if (_headerSpace.getIcmpTypes().size() > 0) {
      acc = mkFalse();
      for (SubRange subRange : _headerSpace.getIcmpTypes()) {
        BoolExpr bound = subRangeBound(_symbolicPacket.getIcmpType(), subRange);
        acc = mkOr(acc, bound);
      }
      add(acc);
    }

    if (_headerSpace.getNotIcmpTypes().size() > 0) {
      acc = mkTrue();
      for (SubRange subRange : _headerSpace.getNotIcmpTypes()) {
        BoolExpr bound = subRangeBound(_symbolicPacket.getIcmpType(), subRange);
        acc = mkAnd(acc, mkNot(bound));
      }
      add(acc);
    }

    if (_headerSpace.getIcmpCodes().size() > 0) {
      acc = mkFalse();
      for (SubRange subRange : _headerSpace.getIcmpCodes()) {
        BoolExpr bound = subRangeBound(_symbolicPacket.getIcmpCode(), subRange);
        acc = mkOr(acc, bound);
      }
      add(acc);
    }

    if (_headerSpace.getNotIcmpCodes().size() > 0) {
      acc = mkTrue();
      for (SubRange subRange : _headerSpace.getNotIcmpCodes()) {
        BoolExpr bound = subRangeBound(_symbolicPacket.getIcmpCode(), subRange);
        acc = mkAnd(acc, mkNot(bound));
      }
      add(acc);
    }

    if (_headerSpace.getIpProtocols().size() > 0) {
      acc = mkFalse();
      for (IpProtocol ipProtocol : _headerSpace.getIpProtocols()) {
        BoolExpr bound = mkEq(_symbolicPacket.getIpProtocol(), mkInt(ipProtocol.number()));
        acc = mkOr(acc, bound);
      }
      add(acc);
    }

    if (_headerSpace.getNotIpProtocols().size() > 0) {
      acc = mkTrue();
      for (IpProtocol ipProtocol : _headerSpace.getNotIpProtocols()) {
        BoolExpr bound = mkEq(_symbolicPacket.getIpProtocol(), mkInt(ipProtocol.number()));
        acc = mkAnd(acc, mkNot(bound));
      }
      add(acc);
    }

    // TODO: need to implement fragment offsets, Ecns, states, etc
  }

  /*
   * Add various constraints for well-formed environments
   */
  private void addEnvironmentConstraints() {
    // Environment messages are not internal
    getLogicalGraph()
        .getEnvironmentVars()
        .forEach(
            (le, vars) -> {
              BoolExpr x = vars.getBgpInternal();
              if (x != null) {
                add(mkNot(x));
              }
            });

    // Environment messages have 0 value for client id
    getLogicalGraph()
        .getEnvironmentVars()
        .forEach(
            (le, vars) -> {
              if (vars.getClientId() != null) {
                add(vars.getClientId().isNotFromClient());
              }
            });

    // Communities only when send-community is configured
    getLogicalGraph()
        .getEnvironmentVars()
        .forEach(
            (le, vars) -> {
              BgpNeighbor n = getGraph().getEbgpNeighbors().get(le.getEdge());
              if (!n.getSendCommunity()) {
                vars.getCommunities()
                    .forEach(
                        (cvar, b) -> {
                          add(mkNot(b));
                        });
              }
            });

    // mkIf they don't want the environment modeled
    if (_encoder.getNoEnvironment()) {
      getLogicalGraph()
          .getEnvironmentVars()
          .forEach(
              (le, vars) -> {
                add(mkNot(vars.getPermitted()));
                add(mkImplies(vars.getPermitted(), mkEq(vars.getMetric(), mkInt(0))));
              });
    }

    /*
    getLogicalGraph().getEnvironmentVars().forEach((lge, record) -> {
        record.getCommunities().forEach((cvar, var) -> {
            if (var.toString().contains("$") && var.toString()
            .contains("[0-9]") && var.toString().contains("10.160.109.51")) {
                System.out.println("ADDING: " + var);
                add( var );
            }
        });
    });
    */

  }

  /*
   * Compute the network encoding by adding all the
   * relevant constraints.
   */
  void computeEncoding() {
    addBoundConstraints();
    addCommunityConstraints();
    addTransferFunction();
    addHistoryConstraints();
    addBestPerProtocolConstraints();
    addChoicePerProtocolConstraints();
    addBestOverallConstraints();
    addControlForwardingConstraints();
    addDataForwardingConstraints();
    addUnusedDefaultValueConstraints();
    addInactiveLinkConstraints();
    addHeaderSpaceConstraint();
    addEnvironmentConstraints();
  }

  /*
   * Getters and Setters
   */

  Graph getGraph() {
    return _logicalGraph.getGraph();
  }

  Encoder getEncoder() {
    return _encoder;
  }

  Map<String, List<Protocol>> getProtocols() {
    return _optimizations.getProtocols();
  }

  HeaderSpace getHeaderSpace() {
    return _headerSpace;
  }

  String getSliceName() {
    return _sliceName;
  }

  Context getCtx() {
    return _encoder.getCtx();
  }

  Solver getSolver() {
    return _encoder.getSolver();
  }

  Map<String, Expr> getAllVariables() {
    return _encoder.getAllVariables();
  }

  Optimizations getOptimizations() {
    return _optimizations;
  }

  LogicalGraph getLogicalGraph() {
    return _logicalGraph;
  }

  SymbolicDecisions getSymbolicDecisions() {
    return _symbolicDecisions;
  }

  SymbolicPacket getSymbolicPacket() {
    return _symbolicPacket;
  }

  Map<GraphEdge, BoolExpr> getIncomingAcls() {
    return _inboundAcls;
  }

  Map<GraphEdge, BoolExpr> getOutgoingAcls() {
    return _outboundAcls;
  }

  Table2<String, GraphEdge, BoolExpr> getForwardsAcross() {
    return _forwardsAcross;
  }

  Set<CommunityVar> getAllCommunities() {
    return _allCommunities;
  }

  Map<String, String> getNamedCommunities() {
    return _namedCommunities;
  }

  Map<CommunityVar, List<CommunityVar>> getCommunityDependencies() {
    return _communityDependencies;
  }

  UnsatCore getUnsatCore() {
    return _encoder.getUnsatCore();
  }

  List<SymbolicRecord> getAllSymbolicRecords() {
    return _encoder.getAllSymbolicRecords();
  }

  SymbolicFailures getSymbolicFailures() {
    return _encoder.getSymbolicFailures();
  }
}

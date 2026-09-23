// (P18.183) REFERENCE INSTRUMENT — a JDI "gate-opening census" written by the (CHK.152) read-only census.
//
// It PREDICTS, without building anything, which diagnostics opening a firewall would add: it attaches to a
// compiler JVM started with `-agentlib:jdwp=...,suspend=y`, breaks on every entry to the gate
// (`caasNonSimpleParamChecks`), and for each argument that exits `CAAS_CONTINUE` INVOKES the checker's own
// predicates at the breakpoint (`canUseTypeEngine`, `checkTypeRelatedTo`, the foreign-type-parameter test,
// rest, arity). Its prediction for (CHK.152) step 1 — +0 rows on all 8 profiles, rxjs, marked, cronstrue —
// was EXACT when the gate was then built. Controls it relies on: the instrumented run's diagnostic count
// must equal the uninstrumented one (the probing must not disturb the checker), and it must run against a
// FROZEN copy of the class dir, never the live build output. It is specific to this gate by its method
// names and field reads; copy and adapt it for another. Driver: `scripts/census/jdi-arg-firewall-census.sh`.
//
// Build: javac -d <outDir> scripts/census/JdiArgFirewallCensus.java   (the JDK's jdk.jdi module is used)
import com.sun.jdi.*;
import com.sun.jdi.connect.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;
import java.util.*;
import java.io.*;

public class JdiArgFirewallCensus {
  static VirtualMachine vm;
  static String cls(Value v){ if(v==null) return "null"; String n=v.type().name(); return n.substring(n.lastIndexOf('.')+1); }
  static Value field(ObjectReference o, String name){ if(o==null) return null; Field f=((ReferenceType)o.referenceType()).fieldByName(name); return f==null?null:o.getValue(f);}
  static String str(Value v){ return v instanceof StringReference ? ((StringReference)v).value() : String.valueOf(v); }
  static String symName(ObjectReference t){
    try{ ObjectReference s=(ObjectReference)field(t,"symbol");
      if(s==null && field(t,"target")!=null) s=(ObjectReference)field((ObjectReference)field(t,"target"),"symbol");
      return s==null?"-":str(field(s,"name")); }catch(Exception e){return "?";}
  }
  public static void main(String[] a) throws Exception {
    int port=Integer.parseInt(a[0]); PrintWriter out=new PrintWriter(new FileWriter(a[1]));
    AttachingConnector c=null; for(AttachingConnector x:Bootstrap.virtualMachineManager().attachingConnectors()) if(x.transport().name().equals("dt_socket")) c=x;
    Map<String,Connector.Argument> args=c.defaultArguments(); args.get("port").setValue(""+port); args.get("hostname").setValue("localhost");
    for(int i=0;;i++){ try{ vm=c.attach(args); break;}catch(Exception e){ if(i>100) throw e; Thread.sleep(200);} }
    EventRequestManager erm=vm.eventRequestManager();
    ClassPrepareRequest cpr=erm.createClassPrepareRequest(); cpr.addClassFilter("com.xemantic.typescript.compiler.Checker"); cpr.enable();
    Method mNS=null,mTail=null,mCanUse=null,mRel=null,mAssign=null,mTts=null,mIsArg=null,mForeign=null;
    ObjectReference emptySet=null;
    String pendingKey=null; String pendingLine=null; int hits=0;
    vm.resume();
    outer: while(true){
      EventSet es=vm.eventQueue().remove();
      for(Event ev:es){
        if(ev instanceof VMDeathEvent || ev instanceof VMDisconnectEvent){ if(pendingLine!=null) out.println("CONTINUE\t"+pendingLine); break outer; }
        if(ev instanceof ClassPrepareEvent){
          ReferenceType rt=((ClassPrepareEvent)ev).referenceType();
          for(Method m:rt.methods()){
            switch(m.name()){
              case "caasNonSimpleParamChecks": mNS=m; break;
              case "caasTailGatesAndRelation": mTail=m; break;
              case "canUseTypeEngine": mCanUse=m; break;
              case "checkTypeRelatedTo": if(m.argumentTypeNames().size()==3) mRel=m; break;
              case "getAssignableRelation": mAssign=m; break;
              case "typeToString$com_xemantic_typescript_xemantic_typescript_compiler": mTts=m; break;
              case "isArgCheckableType": mIsArg=m; break;
              case "typeContainsForeignTypeParam": if(m.argumentTypeNames().size()==3 && !m.name().contains("default")) mForeign=m; break;
            }
          }
          for(Method m:new Method[]{mNS,mTail}){ BreakpointRequest br=erm.createBreakpointRequest(m.location()); br.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD); br.enable(); }
          System.err.println("armed "+mNS+" "+mRel+" "+mForeign);
        }
        if(ev instanceof BreakpointEvent){
          BreakpointEvent be=(BreakpointEvent)ev; ThreadReference th=be.thread(); StackFrame f=th.frame(0);
          Method m=be.location().method(); List<Value> av=f.getArgumentValues(); ObjectReference self=f.thisObject();
          ObjectReference arg=(ObjectReference)av.get(0);
          String key=arg==null?"null":(""+arg.uniqueID());
          if(m.equals(mTail)){
            if(pendingLine!=null && key.equals(pendingKey)) out.println("NONE\t"+pendingLine);
            pendingLine=null; pendingKey=null;
          } else {
            if(pendingLine!=null) out.println("CONTINUE\t"+pendingLine);
            hits++;
            ObjectReference pt=(ObjectReference)av.get(1), at=(ObjectReference)av.get(2);
            String fileName=str(av.get(8)); int pos=((IntegerValue)field(arg,"pos")).value();
            String pc=cls(pt), ac=cls(at);
            String extra="";
            boolean objish = ac.matches("Type\\$(Interface|Reference|Object|Intersection|Union)") && pc.matches("Type\\$(Interface|Reference|Object|Intersection|Union)");
            if(objish){
              try{
                int opt=ObjectReference.INVOKE_SINGLE_THREADED;
                if(emptySet==null){ ClassType es2=(ClassType)vm.classesByName("kotlin.collections.EmptySet").get(0); emptySet=(ObjectReference)es2.getValue(es2.fieldByName("INSTANCE")); }
                boolean notSimple=!((BooleanValue)self.invokeMethod(th,mIsArg,List.of(pt),opt)).value();
                boolean canUse=((BooleanValue)self.invokeMethod(th,mCanUse,List.of(at,pt),opt)).value();
                Value rel=self.invokeMethod(th,mAssign,List.of(),opt);
                boolean related=((BooleanValue)self.invokeMethod(th,mRel,List.of(at,pt,rel),opt)).value();
                boolean fp=((BooleanValue)self.invokeMethod(th,mForeign,List.of(pt,emptySet,vm.mirrorOf(0)),opt)).value();
                boolean fa=((BooleanValue)self.invokeMethod(th,mForeign,List.of(at,emptySet,vm.mirrorOf(0)),opt)).value();
                String restArity="";
                if(canUse && !related){
                  try{
                    ObjectReference params=(ObjectReference)av.get(3); int idx=((IntegerValue)av.get(4)).value();
                    ObjectReference argsL=(ObjectReference)av.get(10); ObjectReference sig=(ObjectReference)av.get(5);
                    Method get=((ClassType)params.referenceType()).concreteMethodByName("get","(I)Ljava/lang/Object;");
                    Method size=((ClassType)params.referenceType()).concreteMethodByName("size","()I");
                    Method asize=((ClassType)argsL.referenceType()).concreteMethodByName("size","()I");
                    int np=((IntegerValue)params.invokeMethod(th,size,List.of(),opt)).value();
                    int na=((IntegerValue)argsL.invokeMethod(th,asize,List.of(),opt)).value();
                    int minA=((IntegerValue)field(sig,"minArgumentCount")).value();
                    boolean anyRest=false, thisRest=false;
                    for(int k=0;k<np;k++){ ObjectReference ps=(ObjectReference)params.invokeMethod(th,get,List.of(vm.mirrorOf(k)),opt);
                      ObjectReference vd=(ObjectReference)field(ps,"valueDeclaration"); boolean r=false;
                      if(vd!=null){ Value dt=field(vd,"dotDotDotToken"); r = dt instanceof BooleanValue ? ((BooleanValue)dt).value() : (dt!=null); }
                      if(r){ anyRest=true; if(k==idx) thisRest=true; } }
                    boolean arityOk = na>=minA && (anyRest || na<=np);
                    restArity="rest="+thisRest+"\tarityOk="+arityOk;
                  }catch(Exception e){ restArity="restERR "+e; }
                }
                String as=related?"":str(self.invokeMethod(th,mTts,List.of(at),opt));
                String ps=related?"":str(self.invokeMethod(th,mTts,List.of(pt),opt));
                extra="notSimple="+notSimple+"\tcanUse="+canUse+"\trelated="+related+"\tforeignP="+fp+"\tforeignA="+fa+"\t"+restArity+"\t"+as.replace('\t',' ')+"\t=>\t"+ps.replace('\t',' ');
              }catch(Exception e){ extra="ERR "+e; }
            }
            pendingKey=key;
            pendingLine=fileName+":"+pos+"\t"+cls(arg)+"\t"+ac+"("+symName(at)+")\t"+pc+"("+symName(pt)+")\t"+extra;
          }

        } else if(!(ev instanceof ClassPrepareEvent)) {}
      }
      es.resume();
    }
    out.close(); System.err.println("hits="+hits);
  }
}

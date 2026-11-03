package com.buildertoolbox.fullauto;

import static com.buildertoolbox.fullauto.CarpetCorrectionGate.Action.*;

public final class CarpetCorrectionGateTest {
    public static void main(String[] args) {
        check(CarpetCorrectionGate.decide(true,true,false,0)==MINE,"start a wrong-carpet correction");
        for (int latency=1;latency<=100;latency++) {
            check(CarpetCorrectionGate.decide(true,false,true,latency)==WAIT,
                "predicted air must never permit another attack through to the floor");
            check(CarpetCorrectionGate.decide(false,false,true,latency)==STOP,
                "server air releases mining for replacement");
            check(CarpetCorrectionGate.decide(false,true,true,latency)==STOP,
                "a stale client carpet must not override the server");
        }
        check(CarpetCorrectionGate.decide(true,true,true,20)==MINE,"server rollback retries the carpet");
        check(CarpetCorrectionGate.decide(true,false,true,101)==TIMEOUT,"missing acknowledgement is bounded");
        check(CarpetCorrectionGate.decide(true,true,true,101)==TIMEOUT,"protected carpet cannot trap mining forever");
        check(CarpetCorrectionGate.decide(false,false,true,101)==STOP,"late confirmation wins over timeout");
        check(CarpetCorrectionGate.decide(true,false,false,0)==STOP,"do not start through mismatched client state");
        System.out.println("CarpetCorrectionGate: prediction, server confirmation, rollback and timeout scenarios passed.");
    }
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
}

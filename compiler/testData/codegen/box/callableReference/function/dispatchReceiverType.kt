// TARGET_BACKEND: JVM
// IGNORE_BACKEND_FIR: JVM_IR
// FIR status: IAE: tried to access class test.PX from class BoxKt$box$2
// MODULE: lib
// FILE: X.java
package test;

public class X extends PX {
    public X(String x) { super(x); }
}

// FILE: PX.java
package test;

class PX {
    private final String x;

    PX(String x) { this.x = x; }

    public String foo() { return x; }
}

// MODULE: main(lib)
// FILE: box.kt
import test.X

fun <T, R> T.myLet(block: (T) -> R): R = block(this)

fun box() = X("O").let(X::foo) + X("K").myLet(X::foo)

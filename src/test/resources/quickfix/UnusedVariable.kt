fun unusedVariableTestHarness() {
    val a = 10
    val b = "something"
    val c = computeWithSideEffect()
    val d = 10;
    val e1 = 10; val e2 = "Something"
}

fun computeWithSideEffect(): Int {
    return -1
}

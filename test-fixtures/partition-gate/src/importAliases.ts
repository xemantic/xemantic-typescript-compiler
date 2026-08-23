export namespace AliasCycle {
    import Fst = Snd;
    import Snd = Fst;
}
export namespace HasValue { export var v = "hello"; }
export namespace HidesIt {
    var HasValue = 1;
    import Hidden = HasValue;
}
module 'QuotedNonAmbient' {}

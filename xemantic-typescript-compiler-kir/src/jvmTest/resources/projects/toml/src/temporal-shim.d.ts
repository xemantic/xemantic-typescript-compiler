// A minimal ambient shim for the Temporal proposal, which this library's type
// aliases mention but this program never constructs. Stands in for the
// `temporal-polyfill` types the library depends on.
declare namespace Temporal {
    interface Instant { readonly epochMilliseconds: number }
    interface PlainDate { readonly year: number }
    interface PlainDateTime { readonly year: number }
    interface PlainTime { readonly hour: number }
    interface ZonedDateTime { readonly year: number }
}

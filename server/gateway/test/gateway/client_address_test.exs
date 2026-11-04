defmodule Gateway.ClientAddressTest do
  use ExUnit.Case, async: true

  alias Gateway.ClientAddress

  @trusted ClientAddress.parse_cidrs!("172.18.0.0/16, 2001:db8::/32,10.0.0.7")

  test "takes the last X-Forwarded-For hop from a trusted proxy" do
    headers = [{"x-forwarded-for", "203.0.113.9, 198.51.100.4"}]
    assert ClientAddress.of({172, 18, 0, 3}, headers, @trusted) == {198, 51, 100, 4}
    assert ClientAddress.of({10, 0, 0, 7}, headers, @trusted) == {198, 51, 100, 4}
    assert ClientAddress.of({0x2001, 0xDB8, 0, 0, 0, 0, 0, 1}, headers, @trusted) == {198, 51, 100, 4}

    # The proxy appends to a header the client sent itself; only its own entry, the last, counts.
    two = [{"x-forwarded-for", "198.51.100.4"}, {"x-forwarded-for", "203.0.113.9"}]
    assert ClientAddress.of({172, 18, 0, 3}, two, @trusted) == {203, 0, 113, 9}

    assert ClientAddress.of({172, 18, 0, 3}, [{"x-forwarded-for", "2001:db8::5"}], @trusted) ==
             {0x2001, 0xDB8, 0, 0, 0, 0, 0, 5}
  end

  test "ignores X-Forwarded-For from a peer it does not trust" do
    headers = [{"x-forwarded-for", "198.51.100.4"}]
    assert ClientAddress.of({172, 19, 0, 3}, headers, @trusted) == {172, 19, 0, 3}
    assert ClientAddress.of({10, 0, 0, 8}, headers, @trusted) == {10, 0, 0, 8}
    assert ClientAddress.of({172, 18, 0, 3}, headers, []) == {172, 18, 0, 3}
  end

  test "falls back to the proxy when its header is missing or not an address" do
    assert ClientAddress.of({172, 18, 0, 3}, [], @trusted) == {172, 18, 0, 3}
    assert ClientAddress.of({172, 18, 0, 3}, [{"x-forwarded-for", "unknown"}], @trusted) == {172, 18, 0, 3}
    assert ClientAddress.of({172, 18, 0, 3}, [{"x-real-ip", "198.51.100.4"}], @trusted) == {172, 18, 0, 3}
  end

  test "counts IPv4-mapped IPv6 as the IPv4 address" do
    mapped = {0, 0, 0, 0, 0, 0xFFFF, 0xAC12, 0x0003}
    assert ClientAddress.of(mapped, [{"x-forwarded-for", "198.51.100.4"}], @trusted) == {198, 51, 100, 4}

    assert ClientAddress.of({198, 51, 100, 1}, [{"x-forwarded-for", "::ffff:203.0.113.9"}], @trusted) ==
             {198, 51, 100, 1}
  end

  test "parses TRUSTED_PROXIES and refuses a malformed entry" do
    assert ClientAddress.parse_cidrs!(nil) == []
    assert ClientAddress.parse_cidrs!(" ") == []
    assert ClientAddress.parse_cidrs!("10.0.0.0/8") == [{{10, 0, 0, 0}, 8}]

    for bad <- ["10.0.0.0/33", "10.0.0/8", "proxy", "10.0.0.0/8/1", "10.0.0.0/x", "::/129"] do
      assert_raise ArgumentError, fn -> ClientAddress.parse_cidrs!(bad) end
    end
  end
end

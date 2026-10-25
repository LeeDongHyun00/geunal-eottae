defmodule Gateway.JwksTest do
  use ExUnit.Case, async: true

  alias Gateway.Jwks
  alias GatewayTest.{JwksServer, Keys}

  @moduletag :capture_log

  # Each test gets its own JWKS server and its own cache, so key rotations do not leak between tests.
  setup context do
    key = Keys.generate()
    {server, url} = JwksServer.start([key])
    name = :"jwks_#{context.test}"
    # Fetching as often as needed keeps these tests quick; one test puts the interval back to check it.
    opts = Keyword.merge([name: name, url: url, min_interval: 0], Map.get(context, :jwks_opts, []))
    start_supervised!({Jwks, opts})
    on_exit(fn -> JwksServer.stop(server) end)
    %{key: key, server: server, jwks: name}
  end

  test "returns the user and device of a token the API's key signed", %{key: key, jwks: jwks} do
    user_id = Keys.uuid()
    device_id = Keys.uuid()

    token = Keys.token(key, %{"sub" => user_id, "did" => device_id})

    assert Jwks.verify(token, jwks) == {:ok, %{user_id: user_id, device_id: device_id}}
  end

  test "rejects an expired token", %{key: key, jwks: jwks} do
    token = Keys.token(key, %{"exp" => System.system_time(:second) - 1})

    assert Jwks.verify(token, jwks) == {:error, :expired}
  end

  test "rejects a token that is not valid yet", %{key: key, jwks: jwks} do
    token = Keys.token(key, %{"nbf" => System.system_time(:second) + 60})

    assert Jwks.verify(token, jwks) == {:error, :not_yet_valid}
  end

  test "rejects a token without an expiry", %{key: key, jwks: jwks} do
    claims = %{"sub" => Keys.uuid(), "did" => Keys.uuid()}
    {_meta, token} = key.jwk |> JOSE.JWT.sign(%{"alg" => "ES256", "kid" => key.kid}, claims) |> JOSE.JWS.compact()

    assert Jwks.verify(token, jwks) == {:error, :claims}
  end

  test "rejects a token signed by another key under a known kid", %{key: key, jwks: jwks} do
    impostor = %{Keys.generate() | kid: key.kid}

    assert Jwks.verify(Keys.token(impostor), jwks) == {:error, :signature}
  end

  test "rejects every algorithm but ES256", %{key: key, jwks: jwks} do
    hmac = JOSE.JWK.from_oct(:crypto.strong_rand_bytes(32))
    claims = %{"sub" => Keys.uuid(), "did" => Keys.uuid(), "exp" => System.system_time(:second) + 60}
    {_meta, token} = hmac |> JOSE.JWT.sign(%{"alg" => "HS256", "kid" => key.kid}, claims) |> JOSE.JWS.compact()

    assert Jwks.verify(token, jwks) == {:error, :alg}

    unsigned =
      Base.url_encode64(~s({"alg":"none","kid":"#{key.kid}"}), padding: false) <>
        "." <> Base.url_encode64("{}", padding: false) <> "."

    assert Jwks.verify(unsigned, jwks) == {:error, :alg}
  end

  test "rejects identifiers that are not UUIDs", %{key: key, jwks: jwks} do
    assert Jwks.verify(Keys.token(key, %{"sub" => "alice"}), jwks) == {:error, :claims}
    assert Jwks.verify(Keys.token(key, %{"did" => 42}), jwks) == {:error, :claims}
  end

  test "rejects what is not a token at all", %{jwks: jwks} do
    assert Jwks.verify(nil, jwks) == {:error, :missing}
    assert Jwks.verify("", jwks) == {:error, :malformed}
    assert Jwks.verify("unverified", jwks) == {:error, :malformed}
    assert Jwks.verify("a.b.c", jwks) == {:error, :malformed}
    assert Jwks.verify(Base.url_encode64("{}") <> ".e30.AAAA", jwks) == {:error, :alg}
  end

  test "fetches again for a kid it has not seen, which is how key rotation reaches it", context do
    %{key: key, server: server, jwks: jwks} = context
    assert {:ok, _} = Jwks.verify(Keys.token(key), jwks)
    assert JwksServer.requests(server) == 1

    rotated = Keys.generate()
    JwksServer.set_keys(server, [key, rotated])

    assert {:ok, _} = Jwks.verify(Keys.token(rotated), jwks)
    assert JwksServer.requests(server) == 2
    # Both keys now come from the cache.
    assert {:ok, _} = Jwks.verify(Keys.token(key), jwks)
    assert JwksServer.requests(server) == 2
  end

  @tag jwks_opts: [min_interval: :timer.hours(1)]
  test "does not fetch for every made-up kid", %{key: key, server: server, jwks: jwks} do
    assert {:ok, _} = Jwks.verify(Keys.token(key), jwks)

    for _ <- 1..5 do
      assert Jwks.verify(Keys.token(%{key | kid: "made-up"}), jwks) == {:error, :unknown_kid}
    end

    assert JwksServer.requests(server) == 1
  end

  test "tells a key it does not have apart from a bad signature", %{key: key, jwks: jwks} do
    assert Jwks.verify(Keys.token(key, %{}, %{"kid" => "gone"}), jwks) == {:error, :unknown_kid}
  end

  @tag jwks_opts: [max_age: 50]
  test "drops a key the API no longer publishes once the cache is older than its max age", context do
    %{key: key, server: server, jwks: jwks} = context
    assert {:ok, _} = Jwks.verify(Keys.token(key), jwks)

    JwksServer.set_keys(server, [Keys.generate()])
    Process.sleep(60)

    assert Jwks.verify(Keys.token(key), jwks) == {:error, :unknown_kid}
  end

  test "reports the JWKS unavailable instead of accepting or inventing anything", context do
    %{key: key, server: server, jwks: jwks} = context
    JwksServer.set_status(server, 500)

    assert Jwks.verify(Keys.token(key), jwks) == {:error, :jwks_unavailable}
  end

  test "ignores keys in the set that cannot verify ES256", %{key: key, server: server, jwks: jwks} do
    # An RSA key, an encryption key and a key without a kid sit next to the real one in the document.
    {_meta, rsa} = JOSE.JWK.to_public_map(JOSE.JWK.generate_key({:rsa, 1024}))
    {_meta, enc} = JOSE.JWK.to_public_map(Keys.generate().jwk)
    {_meta, anonymous} = JOSE.JWK.to_public_map(Keys.generate().jwk)
    others = [Map.put(rsa, "kid", "rsa"), Map.merge(enc, %{"kid" => "enc", "use" => "enc"}), anonymous]
    JwksServer.set_document(server, update_in(Keys.jwks([key]), ["keys"], &(&1 ++ others)))

    assert {:ok, _} = Jwks.verify(Keys.token(key), jwks)
    assert Jwks.verify(Keys.token(%{key | kid: "rsa"}), jwks) == {:error, :unknown_kid}
  end
end

defmodule GatewayTest.Keys do
  @moduledoc """
  ES256 key pairs and access tokens for the tests, so no API has to run: the JWKS the gateway fetches comes from
  `GatewayTest.JwksServer`, fed with the public halves of these keys.
  """

  @doc "The key behind the JWKS the running gateway trusts; test/test_helper.exs sets it up."
  def main, do: :persistent_term.get({GatewayTest, :main_key})

  @doc "A fresh P-256 signing key under a random `kid`."
  def generate do
    %{kid: Base.url_encode64(:crypto.strong_rand_bytes(8), padding: false), jwk: JOSE.JWK.generate_key({:ec, "P-256"})}
  end

  @doc "The JWKS document (as a map) for these keys, the way the API publishes it (api-server.md §4)."
  def jwks(keys) do
    %{
      "keys" =>
        for %{kid: kid, jwk: jwk} <- keys do
          {_meta, public} = JOSE.JWK.to_public_map(jwk)
          Map.merge(public, %{"kid" => kid, "use" => "sig", "alg" => "ES256"})
        end
    }
  end

  @doc """
  A compact access token signed by `key`. The claims default to a fresh user and device and a 15-minute expiry
  (api-server.md §4); `header` overrides the protected header.
  """
  def token(key, claims \\ %{}, header \\ %{}) do
    claims = Map.merge(%{"sub" => uuid(), "did" => uuid(), "exp" => System.system_time(:second) + 900}, claims)
    header = Map.merge(%{"alg" => "ES256", "kid" => key.kid}, header)
    {_meta, compact} = key.jwk |> JOSE.JWT.sign(header, claims) |> JOSE.JWS.compact()
    compact
  end

  @doc "A random UUIDv4 string."
  def uuid do
    <<a::32, b::16, _::4, c::12, _::2, d::14, e::48>> = :crypto.strong_rand_bytes(16)
    hex = fn value, digits -> value |> Integer.to_string(16) |> String.downcase() |> String.pad_leading(digits, "0") end
    "#{hex.(a, 8)}-#{hex.(b, 4)}-4#{hex.(c, 3)}-#{hex.(Bitwise.bor(0x8000, d), 4)}-#{hex.(e, 12)}"
  end
end

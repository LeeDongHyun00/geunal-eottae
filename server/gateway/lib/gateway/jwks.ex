defmodule Gateway.Jwks do
  @moduledoc """
  Verifies access tokens against the API's JWKS (gateway.md §2, api-server.md §4).

  The keys live in an ETS table that every socket process reads directly, so verification never queues behind this
  process. The process only fetches: on the first token, on a `kid` the table does not have (the API overlaps keys by
  `kid` when it rotates them), and when the cached set is older than `max_age`. A flood of tokens with made-up `kid`s
  must not turn into a flood of requests to the API, so the fetches are at least `min_interval` apart.
  """
  use GenServer

  require Logger

  @typedoc "What a verified token says about the connection."
  @type identity :: %{user_id: String.t(), device_id: String.t()}

  @type reason ::
          :missing
          | :malformed
          | :alg
          | :unknown_kid
          | :jwks_unavailable
          | :signature
          | :expired
          | :not_yet_valid
          | :claims

  @connect_timeout :timer.seconds(3)
  @uuid ~r/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

  @doc """
  Options: `:name` (default `#{inspect(__MODULE__)}`; also the ETS table name), `:url` (a string or a zero-arity
  function; default: the `:jwks_url` application env, read at each fetch), `:max_age`, `:min_interval` and
  `:fetch_timeout` (how long one HTTP response may take) in milliseconds.
  """
  def start_link(opts) do
    name = Keyword.get(opts, :name, __MODULE__)
    GenServer.start_link(__MODULE__, Keyword.put(opts, :name, name), name: name)
  end

  @doc """
  Checks the signature (ES256 only), `exp` and `nbf`, and returns the `sub` (user) and `did` (device) claims.
  """
  @spec verify(term(), atom()) :: {:ok, identity()} | {:error, reason()}
  def verify(token, server \\ __MODULE__)

  def verify(token, server) when is_binary(token) do
    with {:ok, kid} <- peek_kid(token),
         {:ok, jwk} <- key(server, kid),
         {:ok, claims} <- check_signature(jwk, token),
         :ok <- check_time(claims) do
      identity(claims)
    end
  end

  def verify(_token, _server), do: {:error, :missing}

  defp peek_kid(token) do
    case Jason.decode(JOSE.JWS.peek_protected(token)) do
      {:ok, %{"alg" => "ES256", "kid" => kid}} when is_binary(kid) -> {:ok, kid}
      {:ok, %{"alg" => "ES256"}} -> {:error, :unknown_kid}
      {:ok, _} -> {:error, :alg}
      {:error, _} -> {:error, :malformed}
    end
  rescue
    _ -> {:error, :malformed}
  end

  defp key(server, kid) do
    case cached(server, kid) do
      {:ok, jwk} -> {:ok, jwk}
      :miss -> GenServer.call(server, {:refresh, kid}, :ets.lookup_element(server, :call_timeout, 2))
    end
  catch
    # The fetch, or a fetch queued ahead of it, outlived the call: a stalled API refuses the token, it does not crash
    # the handshake.
    :exit, _ -> {:error, :jwks_unavailable}
  end

  defp cached(server, kid) do
    with [{:fetched_at, at}] <- :ets.lookup(server, :fetched_at),
         true <- now_ms() - at < max_age(server),
         [{^kid, jwk}] <- :ets.lookup(server, kid) do
      {:ok, jwk}
    else
      _ -> :miss
    end
  end

  defp max_age(server), do: :ets.lookup_element(server, :max_age, 2)

  defp check_signature(jwk, token) do
    case JOSE.JWT.verify_strict(jwk, ["ES256"], token) do
      {true, %JOSE.JWT{fields: claims}, _jws} -> {:ok, claims}
      {false, _jwt, _jws} -> {:error, :signature}
      _ -> {:error, :malformed}
    end
  rescue
    _ -> {:error, :malformed}
  end

  defp check_time(claims) do
    now = System.system_time(:second)

    case claims do
      %{"exp" => exp} when not is_integer(exp) -> {:error, :claims}
      %{"exp" => exp} when exp <= now -> {:error, :expired}
      %{"nbf" => nbf} when is_integer(nbf) and nbf > now -> {:error, :not_yet_valid}
      %{"exp" => _} -> :ok
      _ -> {:error, :claims}
    end
  end

  defp identity(%{"sub" => user_id, "did" => device_id}) when is_binary(user_id) and is_binary(device_id) do
    if user_id =~ @uuid and device_id =~ @uuid do
      {:ok, %{user_id: user_id, device_id: device_id}}
    else
      {:error, :claims}
    end
  end

  defp identity(_claims), do: {:error, :claims}

  ## Server

  @impl true
  def init(opts) do
    name = Keyword.fetch!(opts, :name)
    table = :ets.new(name, [:named_table, :protected, :set, read_concurrency: true])
    fetch_timeout = Keyword.get(opts, :fetch_timeout, :timer.seconds(5))
    :ets.insert(table, {:max_age, Keyword.get(opts, :max_age, :timer.hours(1))})
    # A caller may wait for its own fetch and for one already running (connect plus response each).
    :ets.insert(table, {:call_timeout, 2 * (@connect_timeout + fetch_timeout) + 500})

    {:ok,
     %{
       table: table,
       url: Keyword.get(opts, :url, fn -> Application.fetch_env!(:gateway, :jwks_url) end),
       min_interval: Keyword.get(opts, :min_interval, :timer.seconds(5)),
       fetch_timeout: fetch_timeout,
       last_attempt: nil
     }}
  end

  @impl true
  def handle_call({:refresh, kid}, _from, state) do
    # Several sockets may have missed the same key at once; the first one's fetch serves them all.
    case cached(state.table, kid) do
      {:ok, jwk} -> {:reply, {:ok, jwk}, state}
      :miss -> refresh(kid, state)
    end
  end

  defp refresh(kid, %{last_attempt: last} = state) when is_integer(last) do
    if now_ms() - last < state.min_interval do
      {:reply, {:error, :unknown_kid}, state}
    else
      refresh(kid, %{state | last_attempt: nil})
    end
  end

  defp refresh(kid, state) do
    state = %{state | last_attempt: now_ms()}

    case fetch(url(state.url), state.fetch_timeout) do
      {:ok, keys} ->
        :ets.insert(state.table, Map.to_list(keys))
        :ets.insert(state.table, {:fetched_at, now_ms()})
        stale = for {k, _} <- :ets.tab2list(state.table), is_binary(k), not Map.has_key?(keys, k), do: k
        Enum.each(stale, &:ets.delete(state.table, &1))

        case keys do
          %{^kid => jwk} -> {:reply, {:ok, jwk}, state}
          _ -> {:reply, {:error, :unknown_kid}, state}
        end

      {:error, reason} ->
        Logger.warning("could not fetch the JWKS: #{inspect(reason)}")
        {:reply, {:error, :jwks_unavailable}, state}
    end
  end

  defp url(fun) when is_function(fun, 0), do: fun.()
  defp url(url) when is_binary(url), do: url

  defp fetch(url, timeout) do
    request = {String.to_charlist(url), [{~c"accept", ~c"application/json"}]}
    http_opts = [timeout: timeout, connect_timeout: @connect_timeout] ++ ssl_opts(URI.parse(url))

    case :httpc.request(:get, request, http_opts, body_format: :binary) do
      {:ok, {{_, 200, _}, _headers, body}} -> parse(body)
      {:ok, {{_, status, _}, _headers, _body}} -> {:error, {:status, status}}
      {:error, reason} -> {:error, reason}
    end
  end

  # The API is reached over the internal network (gateway.md §7); should that ever be TLS, verify it properly.
  defp ssl_opts(%URI{scheme: "https", host: host}) when is_binary(host) do
    [
      ssl: [
        verify: :verify_peer,
        cacerts: :public_key.cacerts_get(),
        server_name_indication: String.to_charlist(host),
        depth: 3
      ]
    ]
  end

  defp ssl_opts(_uri), do: []

  defp parse(body) do
    case Jason.decode(body) do
      {:ok, %{"keys" => keys}} when is_list(keys) -> {:ok, Map.new(Enum.flat_map(keys, &to_jwk/1))}
      {:ok, _} -> {:error, :no_keys}
      {:error, _} -> {:error, :invalid_json}
    end
  end

  # Only P-256 signing keys with a `kid` can verify an ES256 token; anything else in the set is skipped.
  defp to_jwk(%{"kty" => "EC", "crv" => "P-256", "kid" => kid} = key) when is_binary(kid) do
    with true <- Map.get(key, "use", "sig") == "sig",
         true <- Map.get(key, "alg", "ES256") == "ES256",
         {:ok, jwk} <- from_map(Map.take(key, ["kty", "crv", "x", "y"])) do
      [{kid, jwk}]
    else
      _ -> []
    end
  end

  defp to_jwk(_key), do: []

  defp from_map(map) do
    {:ok, JOSE.JWK.from_map(map)}
  rescue
    _ -> :error
  end

  defp now_ms, do: System.monotonic_time(:millisecond)
end

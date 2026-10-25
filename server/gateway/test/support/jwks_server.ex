defmodule GatewayTest.JwksServer do
  @moduledoc """
  Serves a JWKS over HTTP on a loopback port, standing in for the API's `/.well-known/jwks.json`. The keys can be
  swapped while it runs, and it counts the requests, so the tests can see when the gateway refetches.
  """

  alias GatewayTest.Keys

  @doc "Starts a server for `keys` (see `GatewayTest.Keys.jwks/1`) and returns `{server, url}`."
  def start(keys) do
    {:ok, state} = Agent.start_link(fn -> %{document: Keys.jwks(keys), status: 200, requests: 0} end)

    {:ok, listener} =
      Bandit.start_link(plug: {__MODULE__.Plug, state}, ip: :loopback, port: 0, startup_log: false, scheme: :http)

    {:ok, {_address, port}} = ThousandIsland.listener_info(listener)
    {%{state: state, listener: listener}, "http://127.0.0.1:#{port}/.well-known/jwks.json"}
  end

  @doc "Stops the server; both processes are linked to whoever started it, so they may be gone already."
  def stop(%{state: state, listener: listener}) do
    for pid <- [state, listener], Process.alive?(pid) do
      try do
        GenServer.stop(pid)
      catch
        :exit, _ -> :ok
      end
    end

    :ok
  end

  def set_keys(%{state: state}, keys), do: Agent.update(state, &%{&1 | document: Keys.jwks(keys)})

  @doc "Serves this exact JWKS document (a map) from now on."
  def set_document(%{state: state}, document), do: Agent.update(state, &%{&1 | document: document})

  @doc "Every request gets this HTTP status from now on."
  def set_status(%{state: state}, status), do: Agent.update(state, &%{&1 | status: status})

  def requests(%{state: state}), do: Agent.get(state, & &1.requests)

  defmodule Plug do
    @moduledoc false
    @behaviour Elixir.Plug

    import Elixir.Plug.Conn

    @impl true
    def init(state), do: state

    @impl true
    def call(%{method: "GET", path_info: [".well-known", "jwks.json"]} = conn, state) do
      %{document: document, status: status} = Agent.get_and_update(state, &{&1, %{&1 | requests: &1.requests + 1}})

      conn
      |> put_resp_content_type("application/json")
      |> send_resp(status, Jason.encode!(document))
    end

    def call(conn, _state), do: send_resp(conn, 404, "")
  end
end

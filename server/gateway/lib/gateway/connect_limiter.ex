defmodule Gateway.ConnectLimiter do
  @moduledoc """
  Caps socket connection attempts per client address (gateway.md §7: 30 a minute). Attempts are counted in fixed
  windows in a public ETS table that the sockets update themselves; this process only owns the table and drops the
  windows that have passed.
  """
  use GenServer

  @doc "Options: `:name` (also the table name), `:limit` (default 30), `:window` in milliseconds (default a minute)."
  def start_link(opts) do
    name = Keyword.get(opts, :name, __MODULE__)
    GenServer.start_link(__MODULE__, Keyword.put(opts, :name, name), name: name)
  end

  @doc "Counts one attempt from `address` and says whether it is still within the limit."
  @spec allow?(:inet.ip_address() | term(), atom()) :: boolean()
  def allow?(address, limiter \\ __MODULE__) do
    [{:config, limit, window}] = :ets.lookup(limiter, :config)
    bucket = div(System.system_time(:millisecond), window)
    :ets.update_counter(limiter, {address, bucket}, {2, 1}, {{address, bucket}, 0}) <= limit
  end

  @impl true
  def init(opts) do
    name = Keyword.fetch!(opts, :name)
    window = Keyword.get(opts, :window, :timer.minutes(1))
    table = :ets.new(name, [:named_table, :public, :set, write_concurrency: true])
    :ets.insert(table, {:config, Keyword.get(opts, :limit, 30), window})
    Process.send_after(self(), :sweep, window)
    {:ok, %{table: table, window: window}}
  end

  @impl true
  def handle_info(:sweep, state) do
    current = div(System.system_time(:millisecond), state.window)
    :ets.select_delete(state.table, [{{{:_, :"$1"}, :_}, [{:<, :"$1", current}], [true]}])
    Process.send_after(self(), :sweep, state.window)
    {:noreply, state}
  end
end

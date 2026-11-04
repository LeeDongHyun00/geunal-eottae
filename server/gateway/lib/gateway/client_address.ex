defmodule Gateway.ClientAddress do
  @moduledoc """
  The address a connection attempt is counted under (gateway.md §7). Behind a reverse proxy every connection comes
  from the proxy, so one busy proxy would use up the per-address limit for every device behind it. When the peer is a
  proxy the operator trusts (`TRUSTED_PROXIES`, CIDRs separated by commas), the last X-Forwarded-For hop names the
  client instead: that is the one the trusted proxy wrote itself, whereas earlier hops come from the client and could
  be anything. Any other peer's X-Forwarded-For is ignored, so a device cannot pick its own key.
  """
  import Bitwise

  @type cidr :: {:inet.ip_address(), non_neg_integer()}

  @doc "Parses `TRUSTED_PROXIES`; raises on anything that is not an address or a CIDR, so a typo stops the boot."
  @spec parse_cidrs!(String.t() | nil) :: [cidr]
  def parse_cidrs!(nil), do: []

  def parse_cidrs!(string) do
    string
    |> String.split(",")
    |> Enum.map(&String.trim/1)
    |> Enum.reject(&(&1 == ""))
    |> Enum.map(&parse_cidr!/1)
  end

  @doc "Stores the trusted proxies `of/2` uses; called once at startup."
  @spec put_trusted([cidr]) :: :ok
  def put_trusted(cidrs), do: :persistent_term.put({__MODULE__, :trusted}, cidrs)

  @doc "The client behind `peer`, given the request's `x-` headers (Phoenix's `:x_headers` connect info)."
  @spec of(:inet.ip_address(), [{String.t(), String.t()}], [cidr]) :: :inet.ip_address()
  def of(peer, x_headers, trusted \\ :persistent_term.get({__MODULE__, :trusted}, [])) do
    peer = unmap(peer)

    if Enum.any?(trusted, &in_cidr?(peer, &1)) do
      last_forwarded(x_headers) || peer
    else
      peer
    end
  end

  defp last_forwarded(headers) do
    hop =
      for({"x-forwarded-for", value} <- headers, hop <- String.split(value, ","), do: String.trim(hop))
      |> List.last()

    with hop when is_binary(hop) <- hop,
         {:ok, address} <- :inet.parse_strict_address(String.to_charlist(hop)) do
      unmap(address)
    else
      _ -> nil
    end
  end

  defp parse_cidr!(cidr) do
    {address, bits} =
      case String.split(cidr, "/") do
        [address] -> {address, nil}
        [address, bits] -> {address, bits}
        _ -> invalid!(cidr)
      end

    with {:ok, ip} <- :inet.parse_strict_address(String.to_charlist(address)),
         max = width(ip),
         {bits, ""} <- if(bits, do: Integer.parse(bits), else: {max, ""}),
         true <- bits in 0..max do
      {ip, bits}
    else
      _ -> invalid!(cidr)
    end
  end

  defp invalid!(cidr), do: raise(ArgumentError, "not an address or CIDR in TRUSTED_PROXIES: #{inspect(cidr)}")

  defp in_cidr?(ip, {net, bits}) when tuple_size(ip) == tuple_size(net) do
    shift = width(ip) - bits
    to_integer(ip) >>> shift == to_integer(net) >>> shift
  end

  defp in_cidr?(_ip, _cidr), do: false

  # A dual-stack listener reports IPv4 clients as IPv4-mapped IPv6; count them as the IPv4 address they are.
  defp unmap({0, 0, 0, 0, 0, 0xFFFF, high, low}), do: {high >>> 8, high &&& 0xFF, low >>> 8, low &&& 0xFF}
  defp unmap(address), do: address

  defp width(ip) when tuple_size(ip) == 4, do: 32
  defp width(_ip), do: 128

  defp to_integer(ip) do
    part = if tuple_size(ip) == 4, do: 8, else: 16
    ip |> Tuple.to_list() |> Enum.reduce(0, fn p, acc -> acc <<< part ||| p end)
  end
end

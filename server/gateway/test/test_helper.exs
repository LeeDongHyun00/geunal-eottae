# Redix connects in the background, so give it five seconds to reach Redis before the tests that need it run.
redis_ready? =
  Enum.reduce_while(1..50, false, fn _, _ ->
    if Redix.command(Gateway.Redis, ["PING"]) == {:ok, "PONG"} do
      {:halt, true}
    else
      Process.sleep(100)
      {:cont, false}
    end
  end)

redis_ready? || raise "Redis at #{Application.fetch_env!(:gateway, :redis_url)} did not answer PING within five seconds"

# The gateway's JWKS comes from a server inside the test run, signed with a key the tests can mint tokens with
# (GatewayTest.Keys.token/1 on GatewayTest.Keys.main/0). Gateway.Jwks reads the URL at its first fetch, which only a
# test triggers.
key = GatewayTest.Keys.generate()
{_server, url} = GatewayTest.JwksServer.start([key])
:persistent_term.put({GatewayTest, :main_key}, key)
Application.put_env(:gateway, :jwks_url, url)

ExUnit.start()

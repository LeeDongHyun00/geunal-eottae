defmodule Gateway.MixProject do
  use Mix.Project

  def project do
    [
      app: :gateway,
      version: "0.1.0",
      elixir: "~> 1.20",
      start_permanent: Mix.env() == :prod,
      elixirc_paths: elixirc_paths(Mix.env()),
      deps: deps(),
      releases: [gateway: [include_executables_for: [:unix]]]
    ]
  end

  def application do
    # :inets and :ssl are for :httpc, which fetches the JWKS.
    [mod: {Gateway.Application, []}, extra_applications: [:logger, :inets, :ssl]]
  end

  # The test helpers (keys, a JWKS server) live in test/support.
  defp elixirc_paths(:test), do: ["lib", "test/support"]
  defp elixirc_paths(_env), do: ["lib"]

  defp deps do
    [
      {:bandit, "~> 1.13"},
      {:credo, "~> 1.7", only: [:dev, :test], runtime: false},
      {:jason, "~> 1.4"},
      {:jose, "~> 1.11"},
      {:phoenix, "~> 1.8.16"},
      {:redix, "~> 1.10"}
    ]
  end
end

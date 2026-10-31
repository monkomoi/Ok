# Freqtrade code excerpt: Part 1/2 of function `_init_ccxt()`

- Original file: `freqtrade/exchange/exchange.py`
- Original lines: 413-466
- The code below is copied verbatim. This is a reading excerpt, not a runnable standalone module.
- This file contains only a contiguous segment of the function. Combine the numbered parts in order for full context.

```python
    def _init_ccxt(
        self, exchange_config: dict[str, Any], sync: bool, ccxt_kwargs: dict[str, Any]
    ) -> ccxt.Exchange:
        """
        Initialize ccxt with given config and return valid ccxt instance.
        """
        # Find matching class for the given exchange name
        name = exchange_config["name"]
        if sync:
            ccxt_module = ccxt
        else:
            ccxt_module = ccxt_pro
            if not is_exchange_known_ccxt(name, ccxt_module):
                # Fall back to async if pro doesn't support this exchange
                import ccxt.async_support as ccxt_async

                ccxt_module = ccxt_async

        if not is_exchange_known_ccxt(name, ccxt_module):
            raise OperationalException(f"Exchange {name} is not supported by ccxt")

        ex_config = {
            "apiKey": exchange_config.get(
                "api_key", exchange_config.get("apiKey", exchange_config.get("key"))
            ),
            "secret": exchange_config.get("secret"),
            "password": exchange_config.get("password"),
            "uid": exchange_config.get("uid"),
            "accountId": exchange_config.get("account_id", exchange_config.get("accountId")),
            # DEX attributes:
            "walletAddress": exchange_config.get(
                "wallet_address", exchange_config.get("walletAddress")
            ),
            "privateKey": exchange_config.get("private_key", exchange_config.get("privateKey")),
        }
        if ccxt_kwargs:
            sanitized = sanitize_config({"exchange": {"ccxt_config": ccxt_kwargs}})
            logger.info("Applying additional ccxt config: %s", sanitized["exchange"]["ccxt_config"])
        if self._ccxt_params:
```

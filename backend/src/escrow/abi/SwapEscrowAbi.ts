export const SwapEscrowAbi = [
  {
    "inputs": [],
    "name": "confirmFiatReceived",
    "outputs": [],
    "stateMutability": "nonpayable",
    "type": "function"
  },
  {
    "inputs": [],
    "name": "markAsFunded",
    "outputs": [],
    "stateMutability": "nonpayable",
    "type": "function"
  },
  {
    "inputs": [],
    "name": "refundSeller",
    "outputs": [],
    "stateMutability": "nonpayable",
    "type": "function"
  },
  {
    "inputs": [
      { "internalType": "address", "name": "buyToken", "type": "address" },
      { "internalType": "uint24",   "name": "poolFee",  "type": "uint24" },
      { "internalType": "uint256",  "name": "minBuyAmount",   "type": "uint256" },
      { "internalType": "uint160",  "name": "sqrtPriceLimitX96", "type": "uint160" }
    ],
    "name": "swapAndRelease",
    "outputs": [],
    "stateMutability": "nonpayable",
    "type": "function"
  },
  {
    "inputs": [
      { "internalType": "address", "name": "_seller",     "type": "address" },
      { "internalType": "address", "name": "_buyer",      "type": "address" },
      { "internalType": "uint256","name": "_amount",      "type": "uint256" },
      { "internalType": "address","name": "_bot",         "type": "address" },
      { "internalType": "address","name": "_swapRouter",  "type": "address" }
    ],
    "stateMutability": "nonpayable",
    "type": "constructor"
  },
  {
    "anonymous": false,
    "inputs": [
      { "indexed": true,  "internalType": "address", "name": "buyToken",  "type": "address" },
      { "indexed": false, "internalType": "uint256", "name": "sellAmount","type": "uint256" },
      { "indexed": false, "internalType": "uint256", "name": "buyAmount", "type": "uint256" },
      { "indexed": true,  "internalType": "address", "name": "recipient", "type": "address" }
    ],
    "name": "TokensSwapped",
    "type": "event"
  },
  { "inputs": [], "name": "amount",      "outputs": [ { "internalType": "uint256", "name": "", "type": "uint256" } ], "stateMutability": "view", "type": "function" },
  { "inputs": [], "name": "bot",         "outputs": [ { "internalType": "address", "name": "", "type": "address" } ], "stateMutability": "view", "type": "function" },
  { "inputs": [], "name": "buyer",       "outputs": [ { "internalType": "address", "name": "", "type": "address" } ], "stateMutability": "view", "type": "function" },
  { "inputs": [], "name": "isFunded",    "outputs": [ { "internalType": "bool",    "name": "", "type": "bool" } ],    "stateMutability": "view", "type": "function" },
  { "inputs": [], "name": "isReleased",  "outputs": [ { "internalType": "bool",    "name": "", "type": "bool" } ],    "stateMutability": "view", "type": "function" },
  { "inputs": [], "name": "seller",      "outputs": [ { "internalType": "address", "name": "", "type": "address" } ], "stateMutability": "view", "type": "function" },
  { "inputs": [], "name": "swapRouter",  "outputs": [ { "internalType": "address", "name": "", "type": "address" } ], "stateMutability": "view", "type": "function" },
  { "inputs": [], "name": "token",       "outputs": [ { "internalType": "address", "name": "", "type": "address" } ], "stateMutability": "view", "type": "function" }
] as const;
export const EscrowFactoryAbi = [
  {
    type: 'constructor',
    inputs: [{ internalType: 'address', name: '_bot', type: 'address' }],
    stateMutability: 'nonpayable',
  },
  {
    type: 'event',
    anonymous: false,
    name: 'EscrowCreated',
    inputs: [
      { indexed: true,  internalType: 'address', name: 'escrowAddress', type: 'address' },
      { indexed: false, internalType: 'address', name: 'seller',        type: 'address' },
      { indexed: false, internalType: 'address', name: 'buyer',         type: 'address' },
      { indexed: false, internalType: 'uint256', name: 'amount',        type: 'uint256' },
      { indexed: false, internalType: 'address', name: 'token',         type: 'address' },
    ],
  },
  {
    type: 'function',
    name: 'bot',
    inputs: [],
    outputs: [{ internalType: 'address', name: '', type: 'address' }],
    stateMutability: 'view',
  },
  {
    type: 'function',
    name: 'createEscrow',
    inputs: [
      { internalType: 'address', name: '_seller', type: 'address' },
      { internalType: 'address', name: '_buyer',  type: 'address' },
      { internalType: 'uint256', name: '_amount', type: 'uint256' },
      { internalType: 'address', name: '_token',  type: 'address' },
    ],
    outputs: [{ internalType: 'address', name: '', type: 'address' }],
    stateMutability: 'nonpayable',
  },
] as const
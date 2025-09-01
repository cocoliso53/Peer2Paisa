 // SPDX-License-Identifier: MIT
 pragma solidity ^0.8.20;

 import "./SimpleEscrow.sol";

 contract EscrowFactory {
	 address public bot;

	 event EscrowCreated(address indexed escrowAddress, address seller, address buyer, uint256 amount, address token);

	 constructor(address _bot) {
		 bot = _bot;
	 }

	 function createEscrow(
		 address _seller,
		 address _buyer,
		 uint256 _amount,
		 address _token
	 ) external returns (address) {
		 require(msg.sender == bot, "Only bot can create escrows");

		 SimpleEscrow escrow = new SimpleEscrow(_seller, _buyer, _amount, bot, _token);
		 emit EscrowCreated(address(escrow), _seller, _buyer, _amount, _token);
		 
		 return address(escrow);
	 }
 }

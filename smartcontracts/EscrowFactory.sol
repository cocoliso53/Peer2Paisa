pragma solidity ^0.8.20;

import "./SwapEscrow.sol";

contract EscrowFactory {
    address public bot;
    address public constant uniRouter = 0xE592427A0AEce92De3Edee1F18E0157C05861564;


    event EscrowCreated(address indexed escrowAddress, address seller, address buyer, uint256 amount);

    constructor(address _bot) {
        bot = _bot;
    }

    function createEscrow(
        address _seller,
        address _buyer,
        uint256 _amount
    ) external returns (address) {
        require(msg.sender == bot, "Only bot can create escrows");

        SwapEscrow escrow = new SwapEscrow(_seller, _buyer, _amount, bot, uniRouter);
        emit EscrowCreated(address(escrow), _seller, _buyer, _amount);

        return address(escrow);
    }
}


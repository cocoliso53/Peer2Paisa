// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

import "https://raw.githubusercontent.com/Uniswap/v3-periphery/main/contracts/interfaces/ISwapRouter.sol";
import "https://raw.githubusercontent.com/Uniswap/v3-periphery/main/contracts/libraries/TransferHelper.sol";


contract SwapEscrow {
    address public seller;
    address public buyer;
    uint256 public amount;
    bool    public isFunded;
    bool    public isReleased;
    address public bot;

    // MXNB token on testnet
    address public constant token = 0xF197FFC28c23E0309B5559e7a166f2c6164C80aA;

    // Uniswap V3 SwapRouter
    ISwapRouter public immutable swapRouter;

    event TokensSwapped(
        address indexed buyToken,
        uint256 sellAmount,
        uint256 buyAmount,
        address indexed recipient
    );

    constructor(
        address _seller,
        address _buyer,
        uint256 _amount,
        address _bot,
        address _swapRouter
    ) {
        seller     = _seller;
        buyer      = _buyer;
        amount     = _amount;
        bot        = _bot;
        swapRouter = ISwapRouter(_swapRouter);
    }

    function markAsFunded() external {
        require(msg.sender == bot, "Only trusted bot can mark as funded");
        require(!isFunded, "Already funded");
        require(IERC20(token).balanceOf(address(this)) >= amount, "Insufficient token balance in escrow");
        isFunded = true;
    }

    function confirmFiatReceived() external {
        require(msg.sender == bot, "Only trusted bot can confirm");
        require(isFunded, "Escrow not funded yet");
        require(!isReleased, "Funds already released");

        isReleased = true;
        require(IERC20(token).transfer(buyer, amount), "Token release failed");
    }

    function refundSeller() external {
        require(msg.sender == bot, "Only bot can refund");
        require(isFunded, "Escrow not funded");
        require(!isReleased, "Already released");

        isReleased = true;
        require(IERC20(token).transfer(seller, amount), "Refund failed");
    }

    /**
     * @dev Swaps all MXNB in this contract for `buyToken` via Uniswap V3, then sends to buyer
     * @param buyToken          Address of token to purchase
     * @param poolFee           Fee tier of the pool (e.g., 3000 for 0.3%)
     * @param minBuyAmount      Minimum amount of buyToken expected (slippage protection)
     * @param sqrtPriceLimitX96 Price limit (pass 0 for no limit)
     */
    function swapAndRelease(
        address buyToken,
        uint24  poolFee,
        uint256 minBuyAmount,
        uint160 sqrtPriceLimitX96
    ) external {
        require(msg.sender == bot, "Only trusted bot can swap and release");
        require(isFunded, "Escrow not funded yet");
        require(!isReleased, "Funds already released");

        // 1) check balance
        uint256 inAmt = IERC20(token).balanceOf(address(this));
        require(inAmt > 0, "No MXNB in escrow");

        // 2) approve router
        TransferHelper.safeApprove(token, address(swapRouter), inAmt);

        // 3) build params and swap
        ISwapRouter.ExactInputSingleParams memory params = ISwapRouter.ExactInputSingleParams({
            tokenIn:           token,
            tokenOut:          buyToken,
            fee:               poolFee,
            recipient:         address(this),
            deadline:          block.timestamp,
            amountIn:          inAmt,
            amountOutMinimum:  minBuyAmount,
            sqrtPriceLimitX96: sqrtPriceLimitX96
        });

        uint256 outAmt = swapRouter.exactInputSingle(params);
        require(outAmt >= minBuyAmount, "Slippage too high");

        // 4) finalize
        isReleased = true;
        require(IERC20(buyToken).transfer(buyer, outAmt), "Token transfer to buyer failed");

        emit TokensSwapped(buyToken, inAmt, outAmt, buyer);
    }
}

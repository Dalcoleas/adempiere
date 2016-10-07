package org.compiere.model;

import java.io.File;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;

import org.adempiere.exceptions.AdempiereException;
import org.compiere.process.DocAction;
import org.compiere.process.DocumentEngine;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.eevolution.model.MPPProductBOM;
import org.eevolution.model.MPPProductBOMLine;


public class MProductionBatch extends X_M_Production_Batch implements DocAction {

	/**
	 * 
	 */
	private static final long serialVersionUID = -151106993468792872L;
	/**	Lines					*/
	private MProduction[]	m_productions = null;
	private MMovement[]	 m_moves = null;
	/** Process Message */
	private String				m_processMsg			= null;
	/** Just Prepared Flag */
	private boolean				m_justPrepared			= false;

	private int					docType_PlannedOrder	= 0;
	private MPBatchLine[]	m_pBatchLines = null;

	private Map<Integer, BigDecimal> mapBatchLine = new HashMap<Integer, BigDecimal>();
	
	public MProductionBatch(Properties ctx, int M_Production_Batch_ID,
			String trxName) {
		super(ctx, M_Production_Batch_ID, trxName);
		// TODO Auto-generated constructor stub
	}

	public boolean hasOpenOrder() {
		
	//	Query.
		return false;
	}
	
	public MProduction[] getHeaders(boolean requery)
	{
		if (m_productions != null && !requery) {
			set_TrxName(m_productions, get_TrxName());
			return m_productions;
		}
		String whereClause = "docstatus in ('DR','IP')";
		List<MProduction> list = new Query(getCtx(), I_M_Production.Table_Name, "M_Production_Batch_ID=?", get_TrxName())
		.setParameters(getM_Production_Batch_ID())
		.setOrderBy(MProduction.COLUMNNAME_M_Production_ID)
		.list();
		//
		m_productions = new MProduction[list.size()];
		list.toArray(m_productions);
		return m_productions;
	}	//	getHeaders


	public MMovement[] getMMovements(boolean requery)
	{
		if (m_moves != null && !requery) {
			set_TrxName(m_moves, get_TrxName());
			return m_moves;
		}
		List<MMovement> list = new Query(getCtx(), I_M_Movement.Table_Name, "M_Production_Batch_ID=?", get_TrxName())
		.setParameters(getM_Production_Batch_ID())
		.setOrderBy(MMovement.COLUMNNAME_M_Movement_ID)
		.list();
		//
		m_moves = new MMovement[list.size()];
		list.toArray(m_moves);
		return m_moves;
	}	//	getHeaders

	
	public MProduction getOpenOrder() {
		MProduction[] productions = getHeaders(true);
		for (MProduction production : productions) {
			if (!production.isProcessed()) {
				return production;
			}
		}
		return null;
	}
	public MProduction createProductionHeader(boolean check) {
		MProduction[] productions = getHeaders(true);
		BigDecimal qtyOrder = getTargetQty();
		BigDecimal qtyCompleted = BigDecimal.ZERO;
		int orderCount = 1;
		MProduction newProdOrder = null;
		
		for (MProduction production : productions) {
			if (!production.isProcessed() && check) {
				//still open production order
				return null;
			}
			qtyOrder = qtyOrder.subtract(production.getProductionQty());
			qtyCompleted = qtyCompleted.add(production.getProductionQty());
			orderCount++;
		}
		setQtyOrdered(qtyOrder);
		setQtyCompleted(qtyCompleted);
		if (qtyOrder.compareTo(BigDecimal.ZERO) > 0) {
			setCountOrder(orderCount);
			newProdOrder = createProductionHeader(orderCount);
		}
		save();

		return newProdOrder;
	}
	
	private MProduction createProductionHeader(int count) {
		MProduction production = new MProduction(getCtx(), 0, get_TrxName());
		production.set_Value(COLUMNNAME_M_Production_Batch_ID, getM_Production_Batch_ID());
		production.setClientOrg(this);
		production.setM_Product_ID(getM_Product_ID());
		production.setDatePromised(getMovementDate());
		production.setMovementDate(getMovementDate());
		production.setM_Locator_ID(getM_Locator_ID());
		production.setProductionQty(getTargetQty().subtract(getQtyCompleted()));
		production.setDocumentNo(getDocumentNo() + String.format("-%02d",count));
		int C_DocType_ID= MDocType.getDocType(MDocType.DOCBASETYPE_MaterialProduction, getAD_Org_ID());
		if (C_DocType_ID ==0)
			C_DocType_ID= MDocType.getDocType(MDocType.DOCBASETYPE_MaterialProduction);
		if (C_DocType_ID ==0 )
			return null;
		production.setC_DocType_ID(C_DocType_ID);
		production.save();
		log.info("M_Production_ID=" + production.getM_Production_ID() + " created.");
		
		return production;
	}
	
	
	@Override
	protected boolean beforeDelete() {
		
		MMovement[] movements = getMMovements(true);
		for (MMovement movement : movements) {
			if (movement.isProcessed()) {
				throw new AdempiereException("Cannot delete Batch No: " + getDocumentNo() + ". Movement DocNo=" + movement.getDocumentNo() + " is already processed");
			}
			for (MMovementLine line : movement.getLines(true)) {
				line.delete(false);
			}
			movement.delete(false);
		}
		MProduction[] headers = getHeaders(true);
		for (MProduction production : headers) {
			if (production.isProcessed()) {
				throw new AdempiereException("Cannot delete Batch No: " + getDocumentNo() + ". Production DocNo=" + production.getDocumentNo() + " is already processed");
			}
			production.delete(false);
		}
		
		return super.beforeDelete();
	}

	@Override
	protected boolean beforeSave(boolean newRecord)
	{
		return super.beforeSave(newRecord);
	}

	@Override
	protected boolean afterSave(boolean newRecord, boolean success)
	{
		if (this.is_ValueChanged(COLUMNNAME_QtyReserved))
		{
			//setReservationOnBatchLine(this.getQtyReserved());
		}

		return super.afterSave(newRecord, success);
	}
	
	@Override
	public boolean processIt(String action) throws Exception
	{
		m_processMsg = null;
		DocumentEngine engine = new DocumentEngine(this, getDocStatus());
		return engine.processIt(action, getDocAction());
	}

	@Override
	public boolean unlockIt()
	{
		if (log.isLoggable(Level.INFO))
			log.info("unlockIt - " + toString());
		setProcessing(false);
		return true;
	}

	@Override
	public boolean invalidateIt()
	{
		if (log.isLoggable(Level.INFO))
			log.info(toString());
		setDocAction(DOCACTION_Prepare);
		return true;
	}

	@Override
	public boolean approveIt()
	{
		return true;
	}

	@Override
	public boolean rejectIt()
	{
		return true;
	}

	@Override
	public boolean reverseCorrectIt()
	{
		return false;
	}

	@Override
	public boolean reverseAccrualIt()
	{
		return false;
	}

	@Override
	public boolean reActivateIt()
	{
		return false;
	}

	@Override
	public String getSummary()
	{
		return getDocumentNo();
	}

	@Override
	public String getDocumentInfo()
	{
		return getDocumentNo();
	}

	@Override
	public File createPDF()
	{
		return null;
	}

	@Override
	public String getProcessMsg()
	{
		return m_processMsg;
	}

	@Override
	public int getDoc_User_ID()
	{
		return getCreatedBy();
	}

	@Override
	public int getC_Currency_ID()
	{
		return MClient.get(getCtx()).getC_Currency_ID();
	}

	@Override
	public BigDecimal getApprovalAmt()
	{
		return BigDecimal.ZERO;
	}

	@Override
	public String prepareIt()
	{
	

		if (log.isLoggable(Level.INFO))
			log.info(toString());
		m_processMsg = ModelValidationEngine.get().fireDocValidate(this, ModelValidator.TIMING_BEFORE_PREPARE);
		if (m_processMsg != null)
			return DocAction.STATUS_Invalid;

		// Std Period open?
		MPeriod.testPeriodOpen(getCtx(), getMovementDate(), MDocType.DOCBASETYPE_ManufacturingOrder, getAD_Org_ID());

		if (getTargetQty().compareTo(Env.ZERO) == 0)
		{
			m_processMsg = "Must be Target Qty";
			return DocAction.STATUS_Invalid;
		}

		m_processMsg = ModelValidationEngine.get().fireDocValidate(this, ModelValidator.TIMING_AFTER_PREPARE);
		if (m_processMsg != null)
			return DocAction.STATUS_Invalid;

		m_justPrepared = true;
		if (!DOCACTION_Complete.equals(getDocAction()))
			setDocAction(DOCACTION_Complete);

		return DocAction.STATUS_InProgress;
	}

	@Override
	public String completeIt()
	{
		// Re-Check
		if (!m_justPrepared)
		{
			String status = prepareIt();
			if (!DocAction.STATUS_InProgress.equals(status))
				return status;
		}

		m_processMsg = ModelValidationEngine.get().fireDocValidate(this, ModelValidator.TIMING_BEFORE_COMPLETE);
		if (m_processMsg != null)
			return DocAction.STATUS_Invalid;

		StringBuilder errors = new StringBuilder();

		// Production Order Processes
		MProduction[] headers = getHeaders(true);
		MProduction header = null;
		if (headers.length == 0)
		{
			header = createProductionHeader(1);
			header.createLines(false);
		}
		else if (headers.length == 1)
		{
			header = headers[0];
			if (getTargetQty().compareTo(header.getProductionQty()) == 0)
			{
				if (!header.isCreated())
					header.createLines(false);
			}
			else
			{
				if (header.isCreated())
					header.deleteLines(get_TrxName());

				header.setProductionQty(getTargetQty());
				header.createLines(false);
			}
		}
		else
		{
			m_processMsg = "Batch having production order not more than one";
			return DocAction.STATUS_Invalid;
		}
		
		header.setIsCreated(true);
		header.saveEx(get_TrxName());

		/*if (!reserveStock((MProduct)getM_Product(), getTargetQty(),0))
		{
			m_processMsg = "Issue while reserving stock on Production Order: " + header.getDocumentInfo();
			return DocAction.STATUS_Invalid;
		}*/

		//if (!orderedStock(getM_Product(), getTargetQty()))
		//{
		//	m_processMsg = "Issue while ordered stock of finished product on Production Order: "
		//			+ header.getDocumentInfo();
		//	return DocAction.STATUS_Invalid;
		//}

		setQtyReserved(getTargetQty());
		setCountOrder(1);
		setQtyOrdered(getTargetQty());

		if (errors.length() > 0)
		{
			m_processMsg = errors.toString();
			return DocAction.STATUS_Invalid;
		}

		// User Validation
		String valid = ModelValidationEngine.get().fireDocValidate(this, ModelValidator.TIMING_AFTER_COMPLETE);
		if (valid != null)
		{
			m_processMsg = valid;
			return DocAction.STATUS_Invalid;
		}

		setProcessed(true);
		setDocAction(DOCACTION_Close);
		setDocStatus(DOCSTATUS_Completed);
		return DocAction.STATUS_Completed;
	}

	@Override
	public boolean closeIt()
	{
		if (log.isLoggable(Level.INFO))
			log.info(toString());
		// Before Close
		m_processMsg = ModelValidationEngine.get().fireDocValidate(this, ModelValidator.TIMING_BEFORE_CLOSE);
		if (m_processMsg != null)
			return false;

		// Stock Reserve or Release
		if (getQtyReserved().compareTo(Env.ZERO) != 0)
		{
			freeStock();
			//orderedStock(getM_Product(), getQtyReserved().negate());
			setQtyReserved(Env.ZERO);
		}

		setProcessed(true);
		setDocAction(DOCACTION_None);

		// After Close
		m_processMsg = ModelValidationEngine.get().fireDocValidate(this, ModelValidator.TIMING_AFTER_CLOSE);
		if (m_processMsg != null)
			return false;
		return true;
	}

	@Override
	public boolean voidIt()
	{
		if (log.isLoggable(Level.INFO))
			log.info(toString());

		// Before Void
		m_processMsg = ModelValidationEngine.get().fireDocValidate(this, ModelValidator.TIMING_BEFORE_VOID);
		if (m_processMsg != null)
			return false;

		if (DOCSTATUS_Closed.equals(getDocStatus()) || DOCSTATUS_Reversed.equals(getDocStatus())
				|| DOCSTATUS_Voided.equals(getDocStatus()))
		{
			m_processMsg = "Document Closed: " + getDocStatus();
			setDocAction(DOCACTION_None);
			return false;
		}

		for (MProduction production: getHeaders(true))
		{
			if (!(MProduction.DOCSTATUS_Closed.equals(production.getDocStatus())
					|| MProduction.DOCSTATUS_Reversed.equals(production.getDocStatus()) || MProduction.DOCSTATUS_Voided
					.equals(production.getDocStatus())))
			{
				if ((MProduction.DOCSTATUS_Drafted.equals(production.getDocStatus())
						|| MProduction.DOCSTATUS_InProgress.equals(production.getDocStatus()) || MProduction.DOCSTATUS_Invalid
						.equals(production.getDocStatus())))
					//reserveStock((MProduct)getM_Product(), getTargetQty(), production.getM_Production_ID());
				{
					if (!production.voidIt())
					{
						m_processMsg = "Document Not Voided: " + production.getDocumentNo();
						return false;
					}
					production.saveEx(get_TrxName());
				}
			}
		}
		
		MProductionBatch pBatch = new MProductionBatch(getCtx(), get_ID(), get_TrxName());

		if (pBatch.getQtyReserved() != null && pBatch.getQtyReserved().compareTo(Env.ZERO) > 0)
		{
			if (!reserveStock((MProduct)getM_Product(), pBatch.getQtyReserved().negate(),0))
			{
				m_processMsg = "Issue while releasing reserved stock";
				return false;
			}
/*
			if (!orderedStock(getM_Product(), pBatch.getQtyReserved().negate()))
			{
				//m_processMsg = "Issue while releasing ordered stock of finished product";
				return false;
			}
		}*/

		setQtyReserved(Env.ZERO);

		// After Void
		m_processMsg = ModelValidationEngine.get().fireDocValidate(this, ModelValidator.TIMING_AFTER_VOID);
		if (m_processMsg != null)
			return false;
		}
		setProcessed(true);
		setDocAction(DOCACTION_None);
		return true;
		
	}

	/**
	 * (un)Reserving stock of components
	 * 
	 * @param fProduct
	 * @param qty
	 * @return
	 */
	public boolean reserveStock(MProduct fProduct, BigDecimal qty, int M_Production_ID)
	{		
		String whereClause = "m_production_ID in (select m_production_ID from m_production where m_production_batch_ID =?)";
		if (M_Production_ID !=0)
			whereClause = whereClause + " AND M_Production_ID=" + M_Production_ID;
		List<MProductionLine> list= new Query(getCtx(), MProductionLine.Table_Name, whereClause, get_TrxName())
				.setParameters(getM_Production_Batch_ID())
				.list();
		for (MProductionLine pLine:list)
		{	
			//	Check/set WH/Org
			//	Binding
			if (pLine.isEndProduct())
				continue;
			BigDecimal target = pLine.getMovementQty();
			BigDecimal difference = (qty.signum()==-1 && getDocAction().equals(MProductionBatch.DOCACTION_Close))
					|| getDocAction().equals(MProductionBatch.DOCACTION_Void)? target
					:target.negate();
			if (difference.signum() == 0)
			{
				continue;
			}
			//	Check Product - Stocked and Item
			MProduct product = pLine.getProduct();
			if (product != null) 
			{
				if (product.isStocked())
				{
					BigDecimal reserved = difference;
					int M_Locator_ID = 0; 
					//	Get Locator to reserve
					if (pLine.getM_AttributeSetInstance_ID() != 0)	//	Get existing Location
						M_Locator_ID = MStorage.getM_Locator_ID (pLine.getM_Locator().getM_Warehouse_ID(), 
								pLine.getM_Product_ID(), pLine.getM_AttributeSetInstance_ID(), 
							pLine.getMovementQty(), get_TrxName());
					//	Get default Location
					if (M_Locator_ID == 0)
					{
						// try to take default locator for product first
						// if it is from the selected warehouse
						MWarehouse wh = MWarehouse.get(getCtx(), pLine.getM_Locator().getM_Warehouse_ID());
						M_Locator_ID = product.getM_Locator_ID();
						if (M_Locator_ID!=0) {
							MLocator locator = new MLocator(getCtx(), product.getM_Locator_ID(), get_TrxName());
							//product has default locator defined but is not from the order warehouse
							if(locator.getM_Warehouse_ID()!=wh.get_ID()) {
								M_Locator_ID = wh.getDefaultLocator().getM_Locator_ID();
							}
						} else {
							M_Locator_ID = wh.getDefaultLocator().getM_Locator_ID();
						}
					}
					//	Update Storage
					if (!MStorage.add(getCtx(), pLine.getM_Locator().getM_Warehouse_ID(), M_Locator_ID, 
							pLine.getM_Product_ID(), 
							0, pLine.getM_AttributeSetInstance_ID(),
						Env.ZERO, reserved, Env.ZERO, get_TrxName()))
						return false;
					
				}	//	stockec
				//	update line
				
				if (!pLine.save(get_TrxName()))
					return false;
				//
			}	//	product
		}	//	reserve inventory
		
		return true;
	}
	public void freeStock()
	{
		int reservationAttributeSetInstance_ID = 0;
		BigDecimal reservationQty = Env.ZERO;
		for (MPBatchLine pBatchLine: getPBatchLines(true))
		{
			if (pBatchLine.getQtyReserved().signum()==0)
				continue;
			if (pBatchLine.isEndProduct())
				continue;
			reservationQty = pBatchLine.getQtyReserved().negate();
			if (!MStorage.add(getCtx(), getM_Locator().getM_Warehouse_ID(),
					getM_Locator_ID(), pBatchLine.getM_Product_ID(), 0, reservationAttributeSetInstance_ID, 
					Env.ZERO, reservationQty, Env.ZERO, get_TrxName()))
				return ;
		}
	}

	/**
	 * (un)ordered stock of finished product
	 * 
	 * @param fProduct - Finished Product
	 * @param orderedQty - on storage ordered stock qty
	 * @return
	 */
	public boolean orderedStock(I_M_Product fProduct, BigDecimal orderedQty)
	{
		if (fProduct.getProductType().compareTo(MProduct.PRODUCTTYPE_Item) != 0)
			return true;

		if (fProduct.isBOM() && fProduct.isPhantom())
			return true;
		BigDecimal difference = getTargetQty().subtract(getQtyReserved());
		if (MStorage.add(getCtx(), getM_Locator().getM_Warehouse_ID(), getM_Locator_ID(), fProduct.getM_Product_ID(),
				0, 0, Env.ZERO, Env.ZERO, orderedQty, get_TrxName()))
		{
			log.log(Level.INFO, "Stock Ordered " + orderedQty + " Qty of " + fProduct.getValue());
		}
		else
		{
			log.log(Level.SEVERE, "Ordered Storage is not updated");
			return false;
		}

		return true;
	}

	private void setReservationOnBatchLine(BigDecimal currQtyReserved)
	{
		mapBatchLine.clear();
		createReservationMap((MProduct) getM_Product(), currQtyReserved);
		createOrUpdateBatchLine(currQtyReserved);
	}

	private void createOrUpdateBatchLine(BigDecimal currQtyReserved)
	{
		// Finished Production
		int id = DB.getSQLValue(get_TrxName(),
				"SELECT pbl.M_PBatch_Line_ID  FROM M_PBatch_Line pbl  WHERE pbl.M_Production_Batch_ID = ? AND pbl.IsEndProduct='Y'",
				this.getM_Production_Batch_ID());
		if (id <= 0)
		{
			MPBatchLine batchLine = new MPBatchLine(getCtx(), 0, get_TrxName());
			batchLine.setM_Product_ID(this.getM_Product_ID());
			batchLine.setIsEndProduct(true);
			batchLine.setM_Production_Batch_ID(this.getM_Production_Batch_ID());
			batchLine.setQtyReserved(currQtyReserved);
			batchLine.saveEx();
		}
		else
		{
			MPBatchLine batchLine = new MPBatchLine(getCtx(), id, get_TrxName());
			batchLine.setQtyReserved(currQtyReserved);
			batchLine.saveEx();
		}

		// Sub-Component
		for (MPBatchLine bline : getPBatchLines(true))
		{
			int productID = bline.getM_Product_ID();
			if (mapBatchLine.containsKey(productID))
			{
				bline.setQtyReserved(mapBatchLine.get(productID));
				bline.saveEx();
				mapBatchLine.remove(productID);
			}
		}

		for (Integer productID : mapBatchLine.keySet())
		{
			if (mapBatchLine.get(productID).compareTo(Env.ZERO) == 0)
			{
				continue;
			}
			else
			{
				MPBatchLine batchLine = new MPBatchLine(getCtx(), 0, get_TrxName());
				batchLine.setM_Product_ID(productID);
				batchLine.setIsEndProduct(false);
				batchLine.setM_Production_Batch_ID(this.getM_Production_Batch_ID());
				batchLine.setQtyReserved(mapBatchLine.get(productID));
				batchLine.saveEx();
			}
		}
	} // createOrUpdateBatchLine

	private void createReservationMap(MProduct finishedProduct, BigDecimal requiredQty)
	{
		
		MPPProductBOM bom = MPPProductBOM.getDefault(finishedProduct, get_TrxName());
		for (MPPProductBOMLine bLine:bom.getLines())
		{
			//int BOMProduct_ID = rs.getInt(1);
			//BigDecimal BOMQty = rs.getBigDecimal(2);
			BigDecimal BOMReseverQty = bLine.getQty().multiply(requiredQty);

			MProduct subProduct = bLine.getProduct();

			if (subProduct.getProductType().compareTo(MProduct.PRODUCTTYPE_Item) != 0)
				continue;

			if (subProduct.isBOM() && subProduct.isPhantom())
			{
				createReservationMap(subProduct, BOMReseverQty);
			}
			else
			{
				if (mapBatchLine.containsKey(subProduct.getM_Product_ID()))
				{
					mapBatchLine.put(subProduct.getM_Product_ID(), mapBatchLine.get(subProduct.getM_Product_ID()).add(BOMReseverQty));
				}
				else
				{
					mapBatchLine.put(subProduct.getM_Product_ID(), BOMReseverQty);
				}
			}
		
			
		}
		// products used in production
		//String sql = "SELECT M_ProductBom_ID, BOMQty  FROM PP_Product_BOM " + " WHERE M_Product_ID="
		//		+ finishedProduct.getM_Product_ID() + " ORDER BY Line";
		/*

		PreparedStatement pstmt = null;
		ResultSet rs = null;

		try
		{
			pstmt = DB.prepareStatement(sql, get_TrxName());
			rs = pstmt.executeQuery();
			while (rs.next())
			{
				int BOMProduct_ID = rs.getInt(1);
				BigDecimal BOMQty = rs.getBigDecimal(2);
				BigDecimal BOMReseverQty = BOMQty.multiply(requiredQty);

				MProduct subProduct = new MProduct(Env.getCtx(), BOMProduct_ID, get_TrxName());

				if (subProduct.getProductType().compareTo(MProduct.PRODUCTTYPE_Item) != 0)
					continue;

				if (subProduct.isBOM() && subProduct.isPhantom())
				{
					createReservationMap(subProduct, BOMReseverQty);
				}
				else
				{
					if (mapBatchLine.containsKey(BOMProduct_ID))
					{
						mapBatchLine.put(BOMProduct_ID, mapBatchLine.get(BOMProduct_ID).add(BOMReseverQty));
					}
					else
					{
						mapBatchLine.put(BOMProduct_ID, BOMReseverQty);
					}
				}
			} // for all bom products
		}
		catch (Exception e)
		{
			throw new AdempiereException("Failed to fill reservation map for batch lines", e);
		}
		finally
		{
			DB.close(rs, pstmt);
			rs = null;
			pstmt = null;
		}*/
	} // createReservationMap

	public MPBatchLine[] getPBatchLines(Boolean requery)
	{
			if (m_pBatchLines != null && !requery) {
				set_TrxName(m_pBatchLines, get_TrxName());
				return m_pBatchLines;
			}
			//
			List<MPBatchLine> list = new Query(getCtx(), MPBatchLine.Table_Name, "M_Production_Batch_ID=?", get_TrxName())
											.setParameters(get_ID())
											.list();
			m_pBatchLines = list.toArray(new MPBatchLine[list.size()]);
			return m_pBatchLines;
		}	//	getLines} // getPBatchLines
	
}